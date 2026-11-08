package io.github.foundry4j.scheduler;

/// Lifecycle states of a scheduled task.
///
/// Every task starts in [#SCHEDULED] and ends in exactly one terminal state:
///
/// ```text
///                 ┌─────────────┐
///        ┌───────►│  SCHEDULED  │◄──────┐
///        │        └──────┬──────┘       │ periodic re-schedule
///        │               │              │ (state stays SCHEDULED)
///     cancel()           │ fires
///        │               ▼
///        │          execution finishes
///        │          ┌───┴───────────────┐
///        ▼          ▼                   ▼
/// ┌────────────┐ ┌───────────┐    ┌──────────┐
/// │ CANCELLED  │ │ COMPLETED │    │  FAILED  │
/// └────────────┘ └───────────┘    └──────────┘
/// ```
///
/// `COMPLETED` is only reachable for one-shot tasks; periodic tasks remain
/// `SCHEDULED` between executions until cancelled or failed.
public enum TaskState {

    /// The task is waiting for its next execution. This is also the state of a
    /// periodic task while one of its executions is running.
    SCHEDULED,

    /// Terminal state: a one-shot task ran to completion successfully.
    COMPLETED,

    /// Terminal state: the task was cancelled via [TaskHandle#cancel()] before
    /// it could finish (it may have executed zero or more times).
    CANCELLED,

    /// Terminal state: an execution threw an uncaught exception. No further
    /// executions will be attempted, mirroring `ScheduledExecutorService`
    /// semantics.
    FAILED;

    /// @return `true` if this state is terminal and the task will never run again
    public boolean isTerminal() {
        return this != SCHEDULED;
    }
}
