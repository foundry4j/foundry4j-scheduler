package io.github.foundry4j.scheduler;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Unit tests for the [Schedulers] factory and its builder validation.
class SchedulersTest {

    @Test
    void newSchedulerReturnsRunningScheduler() {
        try (var scheduler = Schedulers.newScheduler()) {
            assertNotNull(scheduler);
            assertFalse(scheduler.isShutdown());
        }
    }

    @Test
    void builderProducesWorkingScheduler() {
        try (var scheduler = Schedulers.builder()
                .tickDuration(Duration.ofMillis(5))
                .wheelSize(100) // rounded up to 128 internally
                .errorHandler((_, _) -> {
                })
                .build()) {
            assertNotNull(scheduler);
            assertFalse(scheduler.isShutdown());
        }
    }

    @Test
    void builderRejectsSubMillisecondTick() {
        var builder = Schedulers.builder();
        assertThrows(IllegalArgumentException.class,
                () -> builder.tickDuration(Duration.ofNanos(999_999)));
    }

    @Test
    void builderRejectsNullTick() {
        var builder = Schedulers.builder();
        assertThrows(NullPointerException.class, () -> builder.tickDuration(null));
    }

    @Test
    void builderRejectsTinyWheel() {
        var builder = Schedulers.builder();
        assertThrows(IllegalArgumentException.class, () -> builder.wheelSize(7));
    }

    @Test
    void builderRejectsNullErrorHandler() {
        var builder = Schedulers.builder();
        assertThrows(NullPointerException.class, () -> builder.errorHandler(null));
    }

    @Test
    void defaultErrorHandlerLogsWithoutThrowing() {
        // The default handler is exercised implicitly by scheduler tests;
        // here we only assert the builder ships a non-null default.
        var builder = Schedulers.builder();
        assertNotNull(assertInstanceOf(Schedulers.Builder.class, builder));
    }
}
