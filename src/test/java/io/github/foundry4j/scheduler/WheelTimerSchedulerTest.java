package io.github.foundry4j.scheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Behavior tests for [WheelTimerScheduler]. Timing assertions use wide
/// margins so the suite stays stable on loaded machines: every test uses a
/// 10 ms tick and delays of at least 50 ms.
class WheelTimerSchedulerTest {

    private static final Duration TICK = Duration.ofMillis(10);

    private Scheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = Schedulers.builder().tickDuration(TICK).build();
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
    }

    // ------------------------------------------------------------------
    // One-shot execution
    // ------------------------------------------------------------------

    @Test
    void scheduleOnceRunsTaskAndCompletes() throws Exception {
        var ran = new CountDownLatch(1);
        var contextRef = new AtomicReference<TaskContext>();

        TaskHandle handle = scheduler.schedule(ctx -> {
            contextRef.set(ctx);
            ran.countDown();
        }, Trigger.once(Duration.ofMillis(50)));

        assertTrue(ran.await(5, TimeUnit.SECONDS), "task did not run");
        assertEquals(1, contextRef.get().executionCount());
        assertEquals(handle.id(), contextRef.get().taskId());
        awaitTrue(() -> handle.state() == TaskState.COMPLETED);
        assertTrue(handle.isDone());
        assertTrue(handle.nextExecutionTime().isEmpty());
    }

    @Test
    void scheduleOnceHonoursDelay() throws Exception {
        var ran = new CountDownLatch(1);
        long start = System.nanoTime();

        scheduler.scheduleOnce(ran::countDown, Duration.ofMillis(150));

        assertTrue(ran.await(5, TimeUnit.SECONDS), "task did not run");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        // Never early by more than one tick, never absurdly late.
        assertTrue(elapsedMillis >= 150 - TICK.toMillis() - 5, "fired too early: " + elapsedMillis);
        assertTrue(elapsedMillis < 2000, "fired too late: " + elapsedMillis);
    }

    @Test
    void runnableOverloadRuns() throws Exception {
        var ran = new CountDownLatch(1);

        scheduler.schedule(ran::countDown, Trigger.once(Duration.ofMillis(20)));

        assertTrue(ran.await(5, TimeUnit.SECONDS), "task did not run");
    }

    @Test
    void nextExecutionTimeIsPresentWhilePending() {
        TaskHandle handle = scheduler.scheduleOnce(() -> {
        }, Duration.ofSeconds(30));

        var next = handle.nextExecutionTime();
        assertTrue(next.isPresent());
        assertTrue(next.get().isAfter(Instant.now().plusSeconds(20)));

        handle.cancel();
        assertTrue(handle.nextExecutionTime().isEmpty());
    }

    // ------------------------------------------------------------------
    // Periodic execution
    // ------------------------------------------------------------------

    @Test
    void fixedRateRunsRepeatedlyWithIncreasingCounts() throws Exception {
        var contexts = new ConcurrentLinkedQueue<TaskContext>();
        var done = new CountDownLatch(3);

        TaskHandle handle = scheduler.scheduleAtFixedRate(() -> {
        }, Duration.ZERO, Duration.ofMillis(30));
        scheduler.schedule(ctx -> {
            contexts.add(ctx);
            done.countDown();
        }, Trigger.fixedRate(Duration.ZERO, Duration.ofMillis(30)));

        assertTrue(done.await(5, TimeUnit.SECONDS), "task did not run 3 times");
        assertEquals(List.of(1L, 2L, 3L),
                contexts.stream().map(TaskContext::executionCount).toList());
        // Ideal scheduled times must be non-decreasing and drift-free.
        var times = contexts.stream().map(TaskContext::scheduledTime).toList();
        for (int i = 1; i < times.size(); i++) {
            assertFalse(times.get(i).isBefore(times.get(i - 1)));
        }
        handle.cancel();
    }

    @Test
    void fixedDelayWaitsBetweenExecutions() throws Exception {
        var startTimes = new CopyOnWriteArrayList<Long>();
        var done = new CountDownLatch(3);

        scheduler.scheduleWithFixedDelay(() -> {
            startTimes.add(System.nanoTime());
            done.countDown();
        }, Duration.ZERO, Duration.ofMillis(80));

        assertTrue(done.await(5, TimeUnit.SECONDS), "task did not run 3 times");
        for (int i = 1; i < startTimes.size(); i++) {
            long gapMillis = (startTimes.get(i) - startTimes.get(i - 1)) / 1_000_000;
            assertTrue(gapMillis >= 80 - TICK.toMillis() - 5,
                    "gap between runs too small: " + gapMillis);
        }
    }

    @Test
    void fixedRateCadenceDoesNotDrift() throws Exception {
        var contexts = new ConcurrentLinkedQueue<TaskContext>();
        var done = new CountDownLatch(4);

        scheduler.schedule(ctx -> {
            contexts.add(ctx);
            done.countDown();
        }, Trigger.fixedRate(Duration.ofMillis(100), Duration.ofMillis(50)));

        assertTrue(done.await(5, TimeUnit.SECONDS), "task did not run 4 times");
        var scheduled = contexts.stream().map(TaskContext::scheduledTime).toList();
        long spacingMillis = Duration.between(scheduled.getFirst(), scheduled.getLast()).toMillis();
        // 3 intervals of 50 ms, anchored to the ideal schedule.
        assertTrue(spacingMillis >= 140 && spacingMillis <= 160,
                "ideal cadence drifted: " + spacingMillis);
    }

    // ------------------------------------------------------------------
    // Cancellation
    // ------------------------------------------------------------------

    @Test
    void cancelBeforeExecutionPreventsRun() throws Exception {
        var ran = new AtomicBoolean(false);

        TaskHandle handle = scheduler.scheduleOnce(() -> ran.set(true), Duration.ofMillis(50));
        assertTrue(handle.cancel());
        assertFalse(handle.cancel(), "second cancel must return false");
        assertTrue(handle.isCancelled());
        assertTrue(handle.isDone());

        Thread.sleep(200);
        assertFalse(ran.get(), "cancelled task must not run");
    }

    @Test
    void cancelStopsPeriodicTask() throws Exception {
        var runs = new AtomicInteger();
        var ranTwice = new CountDownLatch(2);

        TaskHandle handle = scheduler.scheduleAtFixedRate(() -> {
            runs.incrementAndGet();
            ranTwice.countDown();
        }, Duration.ZERO, Duration.ofMillis(20));

        assertTrue(ranTwice.await(5, TimeUnit.SECONDS));
        assertTrue(handle.cancel());
        int runsAtCancel = runs.get();

        Thread.sleep(200);
        assertEquals(runsAtCancel, runs.get(), "cancelled periodic task kept running");
        assertEquals(TaskState.CANCELLED, handle.state());
    }

    // ------------------------------------------------------------------
    // Failure handling
    // ------------------------------------------------------------------

    @Test
    void failingOneShotBecomesFailed() throws Exception {
        var handlerCalled = new CountDownLatch(1);
        var failureRef = new AtomicReference<Throwable>();
        var contextRef = new AtomicReference<TaskContext>();
        scheduler.close();
        scheduler = Schedulers.builder()
                .tickDuration(TICK)
                .errorHandler((error, ctx) -> {
                    failureRef.set(error);
                    contextRef.set(ctx);
                    handlerCalled.countDown();
                })
                .build();

        var boom = new IllegalStateException("boom");
        TaskHandle handle = scheduler.scheduleOnce(() -> {
            throw boom;
        }, Duration.ofMillis(20));

        assertTrue(handlerCalled.await(5, TimeUnit.SECONDS), "error handler not called");
        assertEquals(boom, failureRef.get());
        assertEquals(handle.id(), contextRef.get().taskId());
        awaitTrue(() -> handle.state() == TaskState.FAILED);
        assertTrue(handle.isDone());
    }

    @Test
    void failingPeriodicTaskDoesNotRunAgain() throws Exception {
        var handlerCalled = new CountDownLatch(1);
        scheduler.close();
        scheduler = Schedulers.builder()
                .tickDuration(TICK)
                .errorHandler((_, _) -> handlerCalled.countDown())
                .build();
        var runs = new AtomicInteger();

        TaskHandle handle = scheduler.scheduleAtFixedRate(() -> {
            runs.incrementAndGet();
            throw new RuntimeException("always fails");
        }, Duration.ZERO, Duration.ofMillis(20));

        assertTrue(handlerCalled.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertEquals(1, runs.get(), "failed periodic task must not be re-scheduled");
        assertEquals(TaskState.FAILED, handle.state());
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Test
    void schedulingAfterShutdownThrows() {
        scheduler.shutdown();
        assertTrue(scheduler.isShutdown());

        assertThrows(SchedulingException.class,
                () -> scheduler.scheduleOnce(() -> {
                }, Duration.ofMillis(10)));
        assertThrows(SchedulingException.class,
                () -> scheduler.schedule(_ -> {
                }, Trigger.fixedRate(Duration.ZERO, Duration.ofSeconds(1))));
    }

    @Test
    void schedulingRejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> scheduler.schedule((Runnable) null, Trigger.once(Duration.ZERO)));
        assertThrows(NullPointerException.class, () -> scheduler.schedule(() -> {
        }, null));
    }

    @Test
    void closeIsIdempotentAndCancelsPending() {
        TaskHandle pending = scheduler.scheduleOnce(() -> {
        }, Duration.ofSeconds(60));

        scheduler.close();
        scheduler.close(); // must not throw

        assertTrue(scheduler.isShutdown());
        assertEquals(TaskState.CANCELLED, pending.state());
    }

    @Test
    void closeWaitsForInFlightExecution() throws Exception {
        var taskStarted = new CountDownLatch(1);
        var taskFinished = new AtomicBoolean(false);

        scheduler.scheduleOnce(() -> {
            taskStarted.countDown();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            taskFinished.set(true);
        }, Duration.ofMillis(20));

        assertTrue(taskStarted.await(5, TimeUnit.SECONDS), "task did not start");
        scheduler.close();
        assertTrue(taskFinished.get(), "close() returned before the running task finished");
    }

    @Test
    void awaitTerminationReturnsAfterTasksFinish() throws Exception {
        var ran = new CountDownLatch(1);
        scheduler.scheduleOnce(ran::countDown, Duration.ofMillis(20));

        assertTrue(scheduler.awaitTermination(Duration.ofSeconds(5)));
        assertTrue(scheduler.isShutdown());
    }

    @Test
    void awaitTerminationRejectsNull() {
        assertThrows(NullPointerException.class, () -> scheduler.awaitTermination(null));
    }

    // ------------------------------------------------------------------
    // Wheel behavior and concurrency
    // ------------------------------------------------------------------

    @Test
    void delaysBeyondOneRevolutionWrapAroundWheel() throws Exception {
        scheduler.close();
        // 8 slots * 10 ms = 80 ms per revolution; a 250 ms delay wraps ~3x.
        scheduler = Schedulers.builder()
                .tickDuration(TICK)
                .wheelSize(8)
                .build();
        var ran = new CountDownLatch(1);
        long start = System.nanoTime();

        scheduler.scheduleOnce(ran::countDown, Duration.ofMillis(250));

        assertTrue(ran.await(5, TimeUnit.SECONDS), "wrapped task did not run");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis >= 250 - TICK.toMillis() - 5, "fired too early: " + elapsedMillis);
        assertTrue(elapsedMillis < 2000, "fired too late: " + elapsedMillis);
    }

    @Test
    void manyTasksAreScheduledInConstantTimeAndAllRun() throws Exception {
        int threads = 16;
        int tasksPerThread = 64;
        int total = threads * tasksPerThread;
        var done = new CountDownLatch(total);

        long submitStart = System.nanoTime();
        try (var pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    for (int i = 0; i < tasksPerThread; i++) {
                        scheduler.scheduleOnce(done::countDown, Duration.ofMillis(30));
                    }
                });
            }
        }
        long submitMillis = (System.nanoTime() - submitStart) / 1_000_000;

        assertTrue(done.await(15, TimeUnit.SECONDS), "not all tasks ran");
        assertTrue(submitMillis < 5000, "submission too slow: " + submitMillis + " ms for " + total + " tasks");
    }

    @Test
    void handlesHaveUniqueIncreasingIds() {
        TaskHandle first = scheduler.scheduleOnce(() -> {
        }, Duration.ofSeconds(30));
        TaskHandle second = scheduler.scheduleOnce(() -> {
        }, Duration.ofSeconds(30));

        assertNotNull(first);
        assertTrue(second.id() > first.id(), "ids must increase");

        first.cancel();
        second.cancel();
    }

    // ------------------------------------------------------------------

    /// Polls a condition with a generous timeout to avoid asserting on
    /// asynchronous state transitions too early.
    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5 s");
            }
            Thread.sleep(5);
        }
    }
}
