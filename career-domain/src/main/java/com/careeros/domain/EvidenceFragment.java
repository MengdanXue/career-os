package com.careeros.domain;

import com.careeros.domain.DomainEnums.LocatorType;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record EvidenceFragment(
    UUID id,
    UUID evidenceId,
    LocatorType locatorType,
    Map<String, Object> locator,
    String verbatimText,
    String contentHash,
    Instant createdAt
) {
    public EvidenceFragment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(evidenceId, "evidenceId");
        Objects.requireNonNull(locatorType, "locatorType");
        locator = locator == null ? Map.of() : Map.copyOf(locator);
        requireText(verbatimText, "verbatimText");
        requireText(contentHash, "contentHash");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
