package com.careeros.domain;

import com.careeros.domain.DomainEnums.ReviewDecision;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ReviewAction(
    UUID id,
    UUID reviewItemId,
    ReviewDecision decision,
    long expectedVersion,
    RecruitmentExtractionProposal originalPayload,
    RecruitmentExtractionProposal correctedPayload,
    String note,
    Instant actedAt
) {
    public ReviewAction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reviewItemId, "reviewItemId");
        Objects.requireNonNull(decision, "decision");
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
        Objects.requireNonNull(originalPayload, "originalPayload");
        Objects.requireNonNull(actedAt, "actedAt");
    }
}
