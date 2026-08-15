package com.careeros.domain.acquisition;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AcquisitionChange(
    UUID id,
    UUID runId,
    UUID sourceId,
    UUID documentId,
    ChangeType changeType,
    String previousFingerprint,
    String currentFingerprint,
    URI canonicalUri,
    Map<String, Object> jobDeltaSummary,
    Instant occurredAt
) {
    public static final String DEACTIVATED_FINGERPRINT = "0".repeat(64);

    public AcquisitionChange {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(changeType, "changeType");
        Objects.requireNonNull(currentFingerprint, "currentFingerprint");
        Objects.requireNonNull(canonicalUri, "canonicalUri");
        jobDeltaSummary = Map.copyOf(new LinkedHashMap<>(jobDeltaSummary == null ? Map.of() : jobDeltaSummary));
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public enum ChangeType { ADDED, UPDATED, DEACTIVATED }
}
