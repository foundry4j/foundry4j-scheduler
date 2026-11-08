package io.github.foundry4j.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/// Immutable information about a single task execution, handed to the task
/// body on every run.
///
/// @param taskId         unique, monotonically increasing identifier assigned by the
///               scheduler at registration time
/// @param scheduledTime  the ideal wall-clock time this execution was planned
///                      for (approximate: it is derived from the monotonic
///                      scheduling clock)
/// @param startTime      the wall-clock time at which this execution actually began
/// @param executionCount 1-based number of executions, including the current
///                       one; always `1` for one-shot tasks
public record TaskContext(long taskId, Instant scheduledTime, Instant startTime, long executionCount) {

    /// Compact constructor rejecting null timestamps.
    public TaskContext {
        Objects.requireNonNull(scheduledTime, "scheduledTime must not be null");
        Objects.requireNonNull(startTime, "startTime must not be null");
        if (executionCount < 1) {
            throw new IllegalArgumentException("executionCount must be >= 1: " + executionCount);
        }
    }

    /// How late this execution started relative to its ideal scheduled time.
    /// A negative value means the timer fired slightly early, which can happen
    /// by up to one tick because of wheel time quantization.
    ///
    /// @return `startTime - scheduledTime`
    public Duration latency() {
        return Duration.between(scheduledTime, startTime);
    }
}
