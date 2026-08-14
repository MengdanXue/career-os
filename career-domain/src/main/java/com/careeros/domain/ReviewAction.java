package com.careeros.domain;

import com.careeros.domain.DomainEnums.ReviewDecision;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ReviewAction(
    UUID id,
    UUID reviewItemId,
    ReviewDecision decision,
    long expectedVersion,
    Map<String, Object> originalPayload,
    Map<String, Object> correctedPayload,
    String note,
    Instant actedAt
) {
    public ReviewAction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reviewItemId, "reviewItemId");
        Objects.requireNonNull(decision, "decision");
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
        originalPayload = originalPayload == null ? Map.of() : Map.copyOf(originalPayload);
        correctedPayload = correctedPayload == null ? Map.of() : Map.copyOf(correctedPayload);
        Objects.requireNonNull(actedAt, "actedAt");
    }
}
