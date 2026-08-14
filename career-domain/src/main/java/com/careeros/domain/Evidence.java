package com.careeros.domain;

import com.careeros.domain.DomainEnums.EvidenceType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Evidence(UUID id, UUID sourceArtifactId, EvidenceType type, String sourceUrl, String sourceTitle, String excerpt, String contentHash, Instant capturedAt) {
    public Evidence { Objects.requireNonNull(id); Objects.requireNonNull(type); require(sourceUrl, "sourceUrl"); Objects.requireNonNull(capturedAt); }
    private static void require(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required"); }
}
