package io.github.foundry4j.scheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/// A high-performance, zero-dependency task scheduler.
///
/// Tasks are registered together with a [Trigger] that describes when they
/// fire, and run on virtual threads so that thousands of concurrent
/// executions never exhaust platform threads. Every registration returns a
/// [TaskHandle] for cancellation and state inspection.
///
/// Typical usage:
///
/// ```java
/// try (var scheduler = Schedulers.newScheduler()) {
///     scheduler.scheduleOnce(() -> sendReminder(), Duration.ofMinutes(30));
///     scheduler.scheduleAtFixedRate(this::collectMetrics, Duration.ZERO, Duration.ofSeconds(10));
/// }
/// ```
///
/// ## Failure semantics
///
/// If a task throws, the failure is reported to the configured error handler
/// and the task transitions to [TaskState#FAILED]; periodic tasks do **not**
/// run again after a failure.
///
/// ## Shutdown semantics
///
/// [#close()] stops accepting new tasks, cancels everything still pending and
/// blocks until all in-flight executions finish. Do not call `close()` from
/// within a task running on this scheduler — the calling task would wait for
/// itself and deadlock.
///
/// Implementations are thread-safe.
public interface Scheduler extends AutoCloseable {

    /// Schedules a task that does not need execution metadata.
    ///
    /// @param task    the action to run
    /// @param trigger when to run it
    /// @return a handle for cancellation and inspection
    /// @throws SchedulingException  if the scheduler is shut down
    /// @throws NullPointerException if any argument is null
    default TaskHandle schedule(Runnable task, Trigger trigger) {
        Objects.requireNonNull(task, "task must not be null");
        return schedule(ctx -> task.run(), trigger);
    }

    /// Schedules a task that receives a [TaskContext] on every execution.
    ///
    /// @param task    the action to run
    /// @param trigger when to run it
    /// @return a handle for cancellation and inspection
    /// @throws SchedulingException  if the scheduler is shut down
    /// @throws NullPointerException if any argument is null
    TaskHandle schedule(Consumer<TaskContext> task, Trigger trigger);

    /// Schedules a one-shot task.
    ///
    /// @param task  the action to run
    /// @param delay time to wait before execution, must not be negative
    /// @return a handle for cancellation and inspection
    default TaskHandle scheduleOnce(Runnable task, Duration delay) {
        return schedule(task, Trigger.once(delay));
    }

    /// Schedules a task on a fixed-rate cadence (see [Trigger.FixedRate]).
    ///
    /// @param task         the action to run
    /// @param initialDelay time to wait before the first execution
    /// @param period       time between consecutive scheduled start times
    /// @return a handle for cancellation and inspection
    default TaskHandle scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period) {
        return schedule(task, Trigger.fixedRate(initialDelay, period));
    }

    /// Schedules a task on a fixed-delay cadence (see [Trigger.FixedDelay]).
    ///
    /// @param task         the action to run
    /// @param initialDelay time to wait before the first execution
    /// @param delay        pause after each execution completes
    /// @return a handle for cancellation and inspection
    default TaskHandle scheduleWithFixedDelay(Runnable task, Duration initialDelay, Duration delay) {
        return schedule(task, Trigger.fixedDelay(initialDelay, delay));
    }

    /// @return `true` once shutdown has been initiated
    boolean isShutdown();

    /// Initiates shutdown: stops accepting new tasks and cancels pending ones.
    /// In-flight executions are allowed to complete. Idempotent.
    void shutdown();

    /// Shuts down and blocks until all in-flight executions complete or the
    /// timeout elapses.
    ///
    /// @param timeout maximum time to wait
    /// @return `true` if the scheduler terminated, `false` on timeout
    /// @throws InterruptedException if the calling thread is interrupted
    boolean awaitTermination(Duration timeout) throws InterruptedException;

    /// Equivalent to calling [#shutdown()] and then blocking until all
    /// in-flight executions finish. Idempotent and never throws.
    @Override
    void close();
}
