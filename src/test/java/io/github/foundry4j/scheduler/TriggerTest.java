package io.github.foundry4j.scheduler;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Unit tests for the [Trigger] sealed hierarchy: factory wiring and
/// argument validation in the record compact constructors.
class TriggerTest {

    @Test
    void onceFactoryCreatesOnceTrigger() {
        var trigger = Trigger.once(Duration.ofSeconds(5));

        var once = assertInstanceOf(Trigger.Once.class, trigger);
        assertEquals(Duration.ofSeconds(5), once.delay());
    }

    @Test
    void fixedRateFactoryCreatesFixedRateTrigger() {
        var trigger = Trigger.fixedRate(Duration.ofSeconds(1), Duration.ofSeconds(2));

        var fixedRate = assertInstanceOf(Trigger.FixedRate.class, trigger);
        assertEquals(Duration.ofSeconds(1), fixedRate.initialDelay());
        assertEquals(Duration.ofSeconds(2), fixedRate.period());
    }

    @Test
    void fixedDelayFactoryCreatesFixedDelayTrigger() {
        var trigger = Trigger.fixedDelay(Duration.ofSeconds(1), Duration.ofSeconds(2));

        var fixedDelay = assertInstanceOf(Trigger.FixedDelay.class, trigger);
        assertEquals(Duration.ofSeconds(1), fixedDelay.initialDelay());
        assertEquals(Duration.ofSeconds(2), fixedDelay.delay());
    }

    @Test
    void onceAcceptsZeroDelay() {
        var once = assertInstanceOf(Trigger.Once.class, Trigger.once(Duration.ZERO));
        assertEquals(Duration.ZERO, once.delay());
    }

    @Test
    void onceRejectsNegativeDelay() {
        assertThrows(IllegalArgumentException.class, () -> Trigger.once(Duration.ofMillis(-1)));
    }

    @Test
    void onceRejectsNullDelay() {
        assertThrows(NullPointerException.class, () -> Trigger.once(null));
    }

    @Test
    void fixedRateRejectsNegativeInitialDelay() {
        assertThrows(IllegalArgumentException.class,
                () -> Trigger.fixedRate(Duration.ofMillis(-1), Duration.ofSeconds(1)));
    }

    @Test
    void fixedRateRejectsZeroPeriod() {
        assertThrows(IllegalArgumentException.class,
                () -> Trigger.fixedRate(Duration.ZERO, Duration.ZERO));
    }

    @Test
    void fixedRateRejectsNegativePeriod() {
        assertThrows(IllegalArgumentException.class,
                () -> Trigger.fixedRate(Duration.ZERO, Duration.ofSeconds(-1)));
    }

    @Test
    void fixedRateRejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> Trigger.fixedRate(null, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> Trigger.fixedRate(Duration.ZERO, null));
    }

    @Test
    void fixedDelayRejectsNegativeInitialDelay() {
        assertThrows(IllegalArgumentException.class,
                () -> Trigger.fixedDelay(Duration.ofMillis(-1), Duration.ofSeconds(1)));
    }

    @Test
    void fixedDelayRejectsZeroDelay() {
        assertThrows(IllegalArgumentException.class,
                () -> Trigger.fixedDelay(Duration.ZERO, Duration.ZERO));
    }

    @Test
    void fixedDelayRejectsNegativeDelay() {
        assertThrows(IllegalArgumentException.class,
                () -> Trigger.fixedDelay(Duration.ZERO, Duration.ofSeconds(-1)));
    }

    @Test
    void fixedDelayRejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> Trigger.fixedDelay(null, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> Trigger.fixedDelay(Duration.ZERO, null));
    }
}
