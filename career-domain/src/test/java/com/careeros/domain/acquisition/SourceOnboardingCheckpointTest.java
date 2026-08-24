package com.careeros.domain.acquisition;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.careeros.domain.acquisition.SourceOnboardingCheckpoint.Checkpoint.BACKFILL_COMPLETE;
import static com.careeros.domain.acquisition.SourceOnboardingCheckpoint.CheckpointStatus.VERIFIED;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SourceOnboardingCheckpointTest {
    @Test
    void verifiedCheckpointRequiresEvidence() {
        assertThatThrownBy(() -> new SourceOnboardingCheckpoint(
            UUID.randomUUID(), BACKFILL_COMPLETE, VERIFIED, " ", Instant.parse("2026-08-24T12:00:00Z")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("evidence");
    }
}
