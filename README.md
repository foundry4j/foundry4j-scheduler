# Foundry4j Scheduler

<img src="docs/logo.svg" alt="Foundry4j Scheduler logo" align="right" width="128" height="128" />

A zero-dependency, high-performance task scheduling library for Java 27,
built on a hashed timing wheel and virtual threads.

![Java 27+](https://img.shields.io/badge/Java-27%2B-ED8B00?logo=openjdk&logoColor=white)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.foundry4j/foundry4j-scheduler?logo=apachemaven&color=blue)](https://central.sonatype.com/artifact/io.github.foundry4j/foundry4j-scheduler)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=foundry4j_foundry4j-scheduler&metric=coverage)](https://sonarcloud.io/summary/new_code?id=foundry4j_foundry4j-scheduler)
[![CI](https://github.com/foundry4j/foundry4j-scheduler/actions/workflows/ci.yml/badge.svg)](https://github.com/foundry4j/foundry4j-scheduler/actions/workflows/ci.yml)

## Features

- **Zero runtime dependencies** — everything is built on the JDK, with a JPMS
  module descriptor (`io.github.foundry4j.scheduler`).
- **O(1) scheduling** — a hashed timing wheel with a lock-free MPSC submission
  queue; producers never block and never contend with the timer thread.
- **Virtual-thread execution** — every task execution runs on its own virtual
  thread, so thousands of concurrent tasks never exhaust platform threads.
- **Three trigger types** — one-shot, fixed-rate (drift-free), and fixed-delay,
  modelled as a sealed record hierarchy.
- **Clean lifecycle** — `AutoCloseable`, idempotent shutdown, cancellation via
  handles, and per-task failure reporting.

## Requirements

- JDK 27 or later

## Installation

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation("io.github.foundry4j:foundry4j-scheduler:1.0.0")
}
```

The library has no transitive dependencies.

## Quick start

```java
import io.github.foundry4j.scheduler.Schedulers;
import io.github.foundry4j.scheduler.TaskHandle;
import io.github.foundry4j.scheduler.Trigger;

import java.time.Duration;

void main() {
    try (var scheduler = Schedulers.newScheduler()) {

        // One-shot: run once, 30 minutes from now.
        scheduler.scheduleOnce(() -> sendReminder(), Duration.ofMinutes(30));

        // Fixed rate: every 10 s, anchored to the ideal schedule (no drift).
        TaskHandle metrics = scheduler.scheduleAtFixedRate(
                () -> collectMetrics(), Duration.ZERO, Duration.ofSeconds(10));

        // Fixed delay: always wait 5 s after the previous run completes.
        scheduler.scheduleWithFixedDelay(
                () -> flushBuffers(), Duration.ZERO, Duration.ofSeconds(5));

        // Full control: the TaskContext exposes id, timing and run count.
        scheduler.schedule(ctx -> IO.println(
                "run #" + ctx.executionCount()
                        + " of task " + ctx.taskId()
                        + ", latency " + ctx.latency()),
                Trigger.fixedRate(Duration.ZERO, Duration.ofSeconds(1)));

        metrics.cancel();
    }
}
```

## Triggers

| Trigger | Factory | First run | Later runs |
|---------|---------|-----------|------------|
| One-shot | `Trigger.once(delay)` | `now + delay` | none |
| Fixed rate | `Trigger.fixedRate(initialDelay, period)` | `now + initialDelay` | every `period`, anchored to the ideal schedule |
| Fixed delay | `Trigger.fixedDelay(initialDelay, delay)` | `now + initialDelay` | `delay` after each run completes |

All triggers are immutable records with validating compact constructors.
Negative initial delays are rejected; periods and delays must be positive.

## Configuration

```java
Scheduler scheduler = Schedulers.builder()
        .tickDuration(Duration.ofMillis(1))    // timer resolution, default 10 ms
        .wheelSize(1024)                       // wheel slots, default 512 (rounded up to a power of two)
        .errorHandler((error, ctx) -> alert(error, ctx.taskId()))
        .build();
```

- **tickDuration** — the timer's resolution. A task may fire up to one tick
  early because deadlines are quantized to tick boundaries. Smaller ticks cost
  more wake-ups; the minimum is 1 ms.
- **wheelSize** — one wheel revolution covers `tickDuration * wheelSize`.
  Longer delays wrap around the wheel and are stored with a round counter, so
  memory stays constant regardless of how many tasks are scheduled.
- **errorHandler** — invoked when an execution throws. The default logs via
  `System.Logger`.

## Architecture

```text
  producer threads                single ticker thread
┌──────────────────┐   MPSC    ┌────────────────────────────┐
│ schedule(...)    │──────────►│ drain queue                │
│ (lock-free offer)│   queue   │ insert into buckets  O(1)  │
└──────────────────┘           │ advance cursor per tick    │
                               │ expire bucket → dispatch   │
                               └─────────────┬──────────────┘
                                             │ submit
                               ┌─────────────▼──────────────┐
                               │ virtual-thread executor    │
                               │ (one vthread per execution)│
                               └────────────────────────────┘
```

- All wheel mutations happen on a single ticker thread, so bucket operations
  need no synchronization; producers communicate through a lock-free queue.
- Scheduling and cancellation are O(1); expiring tasks costs O(1) per tick
  plus the number of due tasks.
- Deadlines ride the monotonic `System.nanoTime()` clock and are immune to
  wall-clock adjustments.
- Delays longer than one wheel revolution carry a round counter that is
  decremented once per revolution — the classic hashed-wheel trade-off.

## Semantics

- **Failure** — if a task throws, the error handler is invoked, the task
  transitions to `FAILED`, and periodic tasks are not rescheduled (mirroring
  `ScheduledExecutorService`).
- **Cancellation** — `TaskHandle.cancel()` guarantees no further executions
  start; an execution already in flight runs to completion.
- **Shutdown** — `close()` stops accepting new tasks (`SchedulingException` is
  thrown), cancels all pending tasks, and blocks until in-flight executions
  finish. Do not call `close()` from within a task running on the same
  scheduler.