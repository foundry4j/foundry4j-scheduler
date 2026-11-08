package io.github.foundry4j.scheduler;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Unit tests for the [TaskContext] record.
class TaskContextTest {

    @Test
    void latencyIsStartMinusScheduled() {
        var scheduled = Instant.parse("2026-11-08T00:00:00Z");
        var started = scheduled.plusMillis(7);
        var context = new TaskContext(1, scheduled, started, 1);

        assertEquals(Duration.ofMillis(7), context.latency());
        assertEquals(1, context.taskId());
        assertEquals(1, context.executionCount());
    }

    @Test
    void rejectsNullTimestamps() {
        var now = Instant.now();
        assertThrows(NullPointerException.class, () -> new TaskContext(1, null, now, 1));
        assertThrows(NullPointerException.class, () -> new TaskContext(1, now, null, 1));
    }

    @Test
    void rejectsNonPositiveExecutionCount() {
        var now = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> new TaskContext(1, now, now, 0));
        assertThrows(IllegalArgumentException.class, () -> new TaskContext(1, now, now, -1));
    }

    @Test
    void latencyCanBeNegativeWhenTimerFiresEarly() {
        var scheduled = Instant.parse("2026-11-08T00:00:00Z");
        var context = new TaskContext(1, scheduled, scheduled.minusMillis(3), 1);

        assertTrue(context.latency().isNegative());
    }
}
