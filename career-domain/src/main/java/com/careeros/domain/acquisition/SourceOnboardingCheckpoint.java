package com.careeros.domain.acquisition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SourceOnboardingCheckpoint(
    UUID sourceId,
    Checkpoint checkpoint,
    CheckpointStatus status,
    String evidence,
    Instant verifiedAt
) {
    public enum Checkpoint {
        REGISTERED,
        CONTRACT_VERIFIED,
        LIVE_SMOKE_VERIFIED,
        BACKFILL_COMPLETE,
        INCREMENTAL_VERIFIED
    }

    public enum CheckpointStatus {
        PENDING,
        VERIFIED,
        FAILED
    }

    public SourceOnboardingCheckpoint {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(status, "status");
        evidence = optional(evidence);
        Objects.requireNonNull(verifiedAt, "verifiedAt");
        if (status == CheckpointStatus.VERIFIED && evidence == null) {
            throw new IllegalArgumentException("evidence is required for a verified checkpoint");
        }
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
