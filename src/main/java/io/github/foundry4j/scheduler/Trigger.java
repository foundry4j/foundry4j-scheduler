package io.github.foundry4j.scheduler;

import java.time.Duration;
import java.util.Objects;

/// Describes **when** a task runs.
///
/// `Trigger` is a sealed hierarchy of three immutable cadences, expressed as
/// records so that schedulers can dispatch over them with exhaustive pattern
/// matching:
///
/// | Trigger | First execution | Subsequent executions |
/// |---------|-----------------|-----------------------|
/// | [Once][Trigger.Once] | `now + delay` | none |
/// | [FixedRate][Trigger.FixedRate] | `now + initialDelay` | every `period`, anchored to the ideal schedule |
/// | [FixedDelay][Trigger.FixedDelay] | `now + initialDelay` | `delay` after the previous run **completes** |
///
/// Instances are created through the static factories:
///
/// ```java
/// Trigger heartbeat = Trigger.fixedRate(Duration.ZERO, Duration.ofSeconds(5));
/// ```
public sealed interface Trigger {

    /// Creates a trigger that fires exactly once after `delay`.
    ///
    /// @param delay time to wait before the single execution, must not be negative
    /// @return a new one-shot trigger
    static Trigger once(Duration delay) {
        return new Once(delay);
    }

    /// Creates a trigger that fires repeatedly with a fixed period measured
    /// between consecutive **scheduled** start times. If a run starts late,
    /// the next run is scheduled closer (or immediately) so the long-term
    /// cadence does not drift.
    ///
    /// @param initialDelay time to wait before the first execution, must not be negative
    /// @param period       time between consecutive scheduled start times, must be positive
    /// @return a new fixed-rate trigger
    static Trigger fixedRate(Duration initialDelay, Duration period) {
        return new FixedRate(initialDelay, period);
    }

    /// Creates a trigger that fires repeatedly, always waiting `delay` after
    /// the previous execution **completed**. The effective period is therefore
    /// `execution time + delay`.
    ///
    /// @param initialDelay time to wait before the first execution, must not be negative
    /// @param delay        time to wait after each execution completes, must be positive
    /// @return a new fixed-delay trigger
    static Trigger fixedDelay(Duration initialDelay, Duration delay) {
        return new FixedDelay(initialDelay, delay);
    }

    /// A trigger that fires exactly once after the given delay.
    ///
    /// @param delay time to wait before execution, never negative
    record Once(Duration delay) implements Trigger {
        /// Compact constructor validating the delay.
        public Once {
            Objects.requireNonNull(delay, "delay must not be null");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("delay must not be negative: " + delay);
            }
        }
    }

    /// A trigger that repeats on a fixed schedule anchored to ideal start times.
    ///
    /// @param initialDelay time to wait before the first execution, never negative
    /// @param period       time between consecutive scheduled start times, always positive
    record FixedRate(Duration initialDelay, Duration period) implements Trigger {
        /// Compact constructor validating both durations.
        public FixedRate {
            Objects.requireNonNull(initialDelay, "initialDelay must not be null");
            Objects.requireNonNull(period, "period must not be null");
            if (initialDelay.isNegative()) {
                throw new IllegalArgumentException("initialDelay must not be negative: " + initialDelay);
            }
            if (period.isZero() || period.isNegative()) {
                throw new IllegalArgumentException("period must be positive: " + period);
            }
        }
    }

    /// A trigger that repeats with a fixed pause after each execution finishes.
    ///
    /// @param initialDelay time to wait before the first execution, never negative
    /// @param delay        pause after each execution completes, always positive
    record FixedDelay(Duration initialDelay, Duration delay) implements Trigger {
        /// Compact constructor validating both durations.
        public FixedDelay {
            Objects.requireNonNull(initialDelay, "initialDelay must not be null");
            Objects.requireNonNull(delay, "delay must not be null");
            if (initialDelay.isNegative()) {
                throw new IllegalArgumentException("initialDelay must not be negative: " + initialDelay);
            }
            if (delay.isZero() || delay.isNegative()) {
                throw new IllegalArgumentException("delay must be positive: " + delay);
            }
        }
    }
}
