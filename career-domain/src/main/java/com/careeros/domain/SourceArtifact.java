package com.careeros.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SourceArtifact(
    UUID id,
    String sha256,
    String mediaType,
    long sizeBytes,
    String storageUri,
    Instant capturedAt
) {
    public SourceArtifact {
        Objects.requireNonNull(id, "id");
        requireText(sha256, "sha256");
        requireText(mediaType, "mediaType");
        if (sizeBytes < 0) throw new IllegalArgumentException("sizeBytes must not be negative");
        requireText(storageUri, "storageUri");
        Objects.requireNonNull(capturedAt, "capturedAt");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
