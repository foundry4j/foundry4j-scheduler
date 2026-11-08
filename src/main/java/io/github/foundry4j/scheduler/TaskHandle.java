package io.github.foundry4j.scheduler;

import java.time.Instant;
import java.util.Optional;

/// A handle to a task registered with a [Scheduler], returned by every
/// `schedule` method. Allows observing the task's lifecycle and cancelling it.
///
/// Handles are thread-safe; all methods may be called from any thread.
public interface TaskHandle {

    /// @return the unique, monotonically increasing identifier of the task
    long id();

    /// @return the current lifecycle state of the task
    TaskState state();

    /// Cancels the task. A cancelled task never transitions back and is
    /// guaranteed not to start any further executions; an execution already
    /// in flight is allowed to finish.
    ///
    /// @return `true` if this call transitioned the task to
    ///         [TaskState#CANCELLED], `false` if the task was already in a
    ///         terminal state
    boolean cancel();

    /// @return `true` if the task has been cancelled
    default boolean isCancelled() {
        return state() == TaskState.CANCELLED;
    }

    /// @return `true` if the task reached a terminal state
    ///         (`COMPLETED`, `CANCELLED` or `FAILED`) and will never run again
    default boolean isDone() {
        return state().isTerminal();
    }

    /// The ideal wall-clock time of the next execution, if the task is still
    /// scheduled. Empty for tasks in a terminal state.
    ///
    /// @return the next scheduled execution time
    Optional<Instant> nextExecutionTime();
}
