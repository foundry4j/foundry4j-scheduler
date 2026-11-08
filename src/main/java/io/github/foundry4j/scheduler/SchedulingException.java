package io.github.foundry4j.scheduler;

/// Thrown when a task cannot be accepted by a [Scheduler], most commonly
/// because the scheduler has already been shut down.
public class SchedulingException extends RuntimeException {

    /// Serialization identifier.
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /// Creates the exception with a detail message.
    ///
    /// @param message human-readable description of the failure
    public SchedulingException(String message) {
        super(message);
    }

    /// Creates the exception with a detail message and a cause.
    ///
    /// @param message human-readable description of the failure
    /// @param cause   the underlying cause
    public SchedulingException(String message, Throwable cause) {
        super(message, cause);
    }
}
