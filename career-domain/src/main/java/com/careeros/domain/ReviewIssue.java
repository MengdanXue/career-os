package com.careeros.domain;

import com.careeros.domain.DomainEnums.ReviewReasonCode;
import java.util.Objects;
import java.util.UUID;

public record ReviewIssue(
    UUID id,
    UUID reviewItemId,
    ReviewReasonCode reasonCode,
    String fieldPath,
    String message,
    UUID evidenceFragmentId
) {
    public ReviewIssue {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reviewItemId, "reviewItemId");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message is required");
    }
}
