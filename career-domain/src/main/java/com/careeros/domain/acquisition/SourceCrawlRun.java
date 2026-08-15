package com.careeros.domain.acquisition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SourceCrawlRun(
    UUID id,
    UUID sourceId,
    RunTrigger trigger,
    RunStatus status,
    Instant startedAt,
    Instant completedAt,
    int discoveredCount,
    int fetchedCount,
    int unchangedCount,
    int addedCount,
    int updatedCount,
    int deactivatedCount,
    int failedCount,
    String errorCode,
    String errorMessage
) {
    public SourceCrawlRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        if (status != RunStatus.RUNNING && completedAt == null) {
            throw new IllegalArgumentException("completedAt is required for terminal runs");
        }
        if (discoveredCount < 0 || fetchedCount < 0 || unchangedCount < 0 || addedCount < 0
            || updatedCount < 0 || deactivatedCount < 0 || failedCount < 0) {
            throw new IllegalArgumentException("run counters cannot be negative");
        }
    }

    public static SourceCrawlRun running(UUID id, UUID sourceId, RunTrigger trigger, Instant at) {
        return new SourceCrawlRun(id, sourceId, trigger, RunStatus.RUNNING, at, null,
            0, 0, 0, 0, 0, 0, 0, null, null);
    }

    public enum RunTrigger { SCHEDULED, MANUAL }
    public enum RunStatus { RUNNING, SUCCEEDED, PARTIALLY_SUCCEEDED, FAILED, SKIPPED_LOCKED }
}
