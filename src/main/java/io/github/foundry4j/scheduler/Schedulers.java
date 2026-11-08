package io.github.foundry4j.scheduler;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BiConsumer;

/// Entry point for creating [Scheduler] instances.
///
/// ```java
/// // Defaults: 10 ms tick, 512-slot wheel, failures logged via System.Logger
/// Scheduler scheduler = Schedulers.newScheduler();
///
/// // Custom configuration
/// Scheduler precise = Schedulers.builder()
///         .tickDuration(Duration.ofMillis(1))
///         .wheelSize(1024)
///         .errorHandler((error, ctx) -> alert(error, ctx.taskId()))
///         .build();
/// ```
public final class Schedulers {

    private Schedulers() {
    }

    /// Creates a scheduler with default settings: a 10 ms tick, a 512-slot
    /// wheel, and an error handler that logs through `System.Logger`.
    ///
    /// @return a new running scheduler
    public static Scheduler newScheduler() {
        return builder().build();
    }

    /// @return a builder pre-initialized with default settings
    public static Builder builder() {
        return new Builder();
    }

    /// Builder for [Scheduler] instances.
    public static final class Builder {

        private Duration tickDuration = Duration.ofMillis(10);
        private int wheelSize = 512;
        private BiConsumer<Throwable, TaskContext> errorHandler = (error, ctx) ->
                System.getLogger("io.github.foundry4j.scheduler")
                        .log(Level.WARNING, "Task " + ctx.taskId() + " failed", error);

        private Builder() {
        }

        /// Sets the timer resolution. Smaller ticks give finer precision at
        /// the cost of more wake-ups. Must be at least 1 ms; default 10 ms.
        ///
        /// @param tickDuration duration of one wheel tick
        /// @return this builder
        public Builder tickDuration(Duration tickDuration) {
            Objects.requireNonNull(tickDuration, "tickDuration must not be null");
            if (tickDuration.toMillis() < 1) {
                throw new IllegalArgumentException("tickDuration must be >= 1 ms: " + tickDuration);
            }
            this.tickDuration = tickDuration;
            return this;
        }

        /// Sets the number of wheel slots. Rounded up to the next power of
        /// two. One full wheel revolution covers
        /// `tickDuration * wheelSize`; longer delays wrap around the wheel and
        /// cost one extra bucket visit per revolution. Must be at least 8;
        /// default 512.
        ///
        /// @param wheelSize number of slots in the timing wheel
        /// @return this builder
        public Builder wheelSize(int wheelSize) {
            if (wheelSize < 8) {
                throw new IllegalArgumentException("wheelSize must be >= 8: " + wheelSize);
            }
            this.wheelSize = wheelSize;
            return this;
        }

        /// Sets the handler invoked when a task execution throws. Exceptions
        /// thrown by the handler itself are swallowed. The default logs via
        /// `System.Logger`.
        ///
        /// @param errorHandler receives the failure and the execution context
        /// @return this builder
        public Builder errorHandler(BiConsumer<Throwable, TaskContext> errorHandler) {
            this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler must not be null");
            return this;
        }

        /// Creates and starts the scheduler.
        ///
        /// @return a new running scheduler
        public Scheduler build() {
            return new WheelTimerScheduler(tickDuration, wheelSize, errorHandler);
        }
    }
}
