package io.github.foundry4j.scheduler;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/// Hashed-timer-wheel [Scheduler] implementation.
///
/// ## Architecture
///
/// ```text
///   producer threads                single ticker thread
/// ┌──────────────────┐   MPSC    ┌────────────────────────────┐
/// │ schedule(...)    │──────────►│ drain queue                │
/// │ (lock-free offer)│  queue    │ insert into buckets  O(1)  │
/// └──────────────────┘           │ advance cursor per tick    │
///                                │ expire bucket → dispatch   │
///                                └─────────────┬──────────────┘
///                                              │ submit
///                                ┌─────────────▼──────────────┐
///                                │ virtual-thread executor    │
///                                │ (one vthread per execution)│
///                                └────────────────────────────┘
/// ```
///
/// All wheel mutations happen on the single ticker thread, so bucket
/// operations need no synchronization at all; producers communicate with the
/// ticker exclusively through a lock-free multi-producer/single-consumer
/// queue. Scheduling a task is therefore O(1) and never blocks, and expiring
/// tasks costs O(1) per tick plus the number of due tasks.
///
/// Delays longer than one wheel revolution are stored with a round counter
/// that is decremented once per revolution — the classic hashed-wheel
/// trade-off that keeps both memory and per-tick work constant.
///
/// ## Time source
///
/// Deadlines are tracked on the monotonic [System#nanoTime()] clock, immune
/// to wall-clock adjustments. The [Instant] values exposed through
/// [TaskContext] and [TaskHandle] are approximations anchored at scheduler
/// start. Quantization rounds deadlines **down** to tick boundaries, so a
/// task may fire up to one tick early but never late (barring scheduling
/// delays of the ticker thread itself).
final class WheelTimerScheduler implements Scheduler {

    private static final System.Logger LOGGER = System.getLogger(WheelTimerScheduler.class.getName());

    /// State transitions use compare-and-set so that `cancel()` from any
    /// thread races safely with terminal transitions on the executor.
    private static final AtomicReferenceFieldUpdater<TaskNode, TaskState> STATE =
            AtomicReferenceFieldUpdater.newUpdater(TaskNode.class, TaskState.class, "state");

    private final long tickNanos;
    private final int wheelSize;
    private final int mask;

    /// Buckets are only ever touched by the ticker thread.
    private final ArrayDeque<TaskNode>[] buckets;

    /// Lock-free inbound channel for new and re-scheduled tasks.
    private final ConcurrentLinkedQueue<TaskNode> inbound = new ConcurrentLinkedQueue<>();

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final BiConsumer<Throwable, TaskContext> errorHandler;
    private final AtomicLong idSequence = new AtomicLong();
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final Thread ticker;

    /// Monotonic clock reading at wheel creation; tick 0 starts here.
    private final long startNanos = System.nanoTime();

    /// Wall-clock anchor paired with [#startNanos] for Instant conversion.
    private final Instant startInstant = Instant.now();

    @SuppressWarnings({"unchecked", "rawtypes"})
    WheelTimerScheduler(Duration tickDuration, int wheelSize, BiConsumer<Throwable, TaskContext> errorHandler) {
        this.tickNanos = tickDuration.toNanos();
        this.wheelSize = nextPowerOfTwo(wheelSize);
        this.mask = this.wheelSize - 1;
        this.errorHandler = errorHandler;
        this.buckets = new ArrayDeque[this.wheelSize];
        for (int i = 0; i < this.wheelSize; i++) {
            buckets[i] = new ArrayDeque<>();
        }
        this.ticker = Thread.ofPlatform()
                .daemon(true)
                .name("foundry4j-scheduler-ticker")
                .unstarted(this::runTicker);
        this.ticker.start();
    }

    private static int nextPowerOfTwo(int value) {
        return 1 << (Integer.SIZE - Integer.numberOfLeadingZeros(value - 1));
    }

    // ------------------------------------------------------------------
    // Scheduler API (called by arbitrary producer threads)
    // ------------------------------------------------------------------

    @Override
    public TaskHandle schedule(Consumer<TaskContext> task, Trigger trigger) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(trigger, "trigger must not be null");
        long initialDelayNanos = switch (trigger) {
            case Trigger.Once(var delay) -> delay.toNanos();
            case Trigger.FixedRate(var initialDelay, _) -> initialDelay.toNanos();
            case Trigger.FixedDelay(var initialDelay, _) -> initialDelay.toNanos();
        };
        var node = new TaskNode(idSequence.incrementAndGet(), task, trigger);
        node.deadlineNanos = System.nanoTime() + initialDelayNanos;
        inbound.offer(node);
        if (shutdown.get()) {
            // Lost the race with shutdown(): make sure the task can never run
            // and reject it, even if the ticker already exited.
            STATE.compareAndSet(node, TaskState.SCHEDULED, TaskState.CANCELLED);
            throw new SchedulingException("scheduler is shut down");
        }
        return node;
    }

    @Override
    public boolean isShutdown() {
        return shutdown.get();
    }

    @Override
    public void shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            ticker.interrupt();
        }
    }

    @Override
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout must not be null");
        shutdown();
        long deadline = System.nanoTime() + timeout.toNanos();
        ticker.join(timeout.toMillis());
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return false;
        }
        executor.shutdown();
        return executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
    }

    @Override
    public void close() {
        shutdown();
        try {
            ticker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // ExecutorService.close() blocks until all in-flight tasks complete.
        executor.close();
    }

    // ------------------------------------------------------------------
    // Ticker thread: the only thread that touches the wheel
    // ------------------------------------------------------------------

    private void runTicker() {
        try {
            long tickIndex = 0;
            long nextTickAt = startNanos;
            while (!shutdown.get()) {
                long sleep = nextTickAt - System.nanoTime();
                if (sleep > 0) {
                    LockSupport.parkNanos(sleep);
                    continue;
                }
                transferInbound(tickIndex);
                processBucket((int) (tickIndex & mask));
                tickIndex++;
                nextTickAt = startNanos + tickIndex * tickNanos;
            }
        } finally {
            cancelPending();
        }
    }

    /// Moves tasks from the lock-free inbound queue into wheel buckets.
    /// Tasks whose deadline already passed are dispatched immediately.
    private void transferInbound(long tickIndex) {
        for (TaskNode node = inbound.poll(); node != null; node = inbound.poll()) {
            if (node.state != TaskState.SCHEDULED) {
                continue; // cancelled while sitting in the queue
            }
            long deadlineTick = (node.deadlineNanos - startNanos) / tickNanos;
            if (deadlineTick <= tickIndex) {
                dispatch(node);
                continue;
            }
            // Rounds to skip = full revolutions between now and the deadline
            // tick. This holds whether or not the target slot is the slot
            // being processed this very tick.
            node.remainingRounds = (deadlineTick - tickIndex) / wheelSize;
            buckets[(int) (deadlineTick & mask)].offer(node);
        }
    }

    /// Expires one bucket: tasks with zero remaining rounds are dispatched,
    /// the rest have their round counter decremented and stay. The bucket
    /// size is snapshotted so re-queued tasks are not visited twice.
    private void processBucket(int slot) {
        var bucket = buckets[slot];
        int pending = bucket.size();
        for (int i = 0; i < pending; i++) {
            TaskNode node = bucket.poll();
            if (node == null) {
                break;
            }
            if (node.state != TaskState.SCHEDULED) {
                continue;
            }
            if (node.remainingRounds > 0) {
                node.remainingRounds--;
                bucket.offer(node);
                continue;
            }
            dispatch(node);
        }
    }

    /// Hands an expired task to the virtual-thread executor.
    private void dispatch(TaskNode node) {
        executor.execute(() -> execute(node));
    }

    /// Runs one execution of the task and re-schedules it if its trigger
    /// demands more firings. Executed on a dedicated virtual thread, so a
    /// single node is never executed concurrently.
    private void execute(TaskNode node) {
        if (node.state != TaskState.SCHEDULED) {
            return; // cancelled between dispatch and execution
        }
        long count = ++node.executionCount;
        var context = new TaskContext(node.id, toInstant(node.deadlineNanos), Instant.now(), count);
        try {
            node.action.accept(context);
        } catch (Throwable failure) {
            if (STATE.compareAndSet(node, TaskState.SCHEDULED, TaskState.FAILED)) {
                reportFailure(failure, context);
            }
            return;
        }
        switch (node.trigger) {
            case Trigger.Once _ -> STATE.compareAndSet(node, TaskState.SCHEDULED, TaskState.COMPLETED);
            case Trigger.FixedRate(_, var period) -> {
                // Anchor to the ideal schedule: no drift even if we ran late.
                node.deadlineNanos += period.toNanos();
                if (node.state == TaskState.SCHEDULED) {
                    inbound.offer(node);
                }
            }
            case Trigger.FixedDelay(_, var delay) -> {
                node.deadlineNanos = System.nanoTime() + delay.toNanos();
                if (node.state == TaskState.SCHEDULED) {
                    inbound.offer(node);
                }
            }
        }
    }

    private void reportFailure(Throwable failure, TaskContext context) {
        try {
            errorHandler.accept(failure, context);
        } catch (Throwable handlerFailure) {
            LOGGER.log(Level.WARNING, "error handler threw", handlerFailure);
        }
    }

    /// Terminal sweep on ticker exit: every task that will never run again is
    /// moved to CANCELLED so handles observe a consistent terminal state.
    private void cancelPending() {
        for (TaskNode node = inbound.poll(); node != null; node = inbound.poll()) {
            STATE.compareAndSet(node, TaskState.SCHEDULED, TaskState.CANCELLED);
        }
        for (var bucket : buckets) {
            for (TaskNode node = bucket.poll(); node != null; node = bucket.poll()) {
                STATE.compareAndSet(node, TaskState.SCHEDULED, TaskState.CANCELLED);
            }
        }
    }

    /// Converts a monotonic deadline into an approximate wall-clock instant.
    private Instant toInstant(long deadlineNanos) {
        return startInstant.plusNanos(deadlineNanos - startNanos);
    }

    // ------------------------------------------------------------------
    // Task node: mutable scheduling state plus the public handle view
    // ------------------------------------------------------------------

    private final class TaskNode implements TaskHandle {

        final long id;
        final Consumer<TaskContext> action;
        final Trigger trigger;

        /// Ideal monotonic time of the next firing; written by the producer
        /// or the executor, read by the ticker via queue happens-before.
        volatile long deadlineNanos;

        /// Full wheel revolutions to skip before this node expires.
        /// Only touched by the ticker thread.
        long remainingRounds;

        /// Completed executions; only touched by the executor thread running
        /// this node (nodes never run concurrently).
        long executionCount;

        volatile TaskState state = TaskState.SCHEDULED;

        TaskNode(long id, Consumer<TaskContext> action, Trigger trigger) {
            this.id = id;
            this.action = action;
            this.trigger = trigger;
        }

        @Override
        public long id() {
            return id;
        }

        @Override
        public TaskState state() {
            return state;
        }

        @Override
        public boolean cancel() {
            return STATE.compareAndSet(this, TaskState.SCHEDULED, TaskState.CANCELLED);
        }

        @Override
        public Optional<Instant> nextExecutionTime() {
            return state == TaskState.SCHEDULED
                    ? Optional.of(toInstant(deadlineNanos))
                    : Optional.empty();
        }
    }
}
