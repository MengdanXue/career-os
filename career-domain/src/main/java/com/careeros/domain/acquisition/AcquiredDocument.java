package com.careeros.domain.acquisition;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record AcquiredDocument(
    UUID id,
    UUID sourceId,
    URI canonicalUri,
    UUID parentDocumentId,
    DocumentKind kind,
    String mediaType,
    String contentFingerprint,
    String etag,
    String lastModified,
    URI storageUri,
    DocumentState state,
    Instant firstSeenAt,
    Instant lastSeenAt,
    Instant lastChangedAt,
    Instant lastGoneAt,
    int consecutiveGoneCount,
    int lastHttpStatus,
    String lastProcessedFingerprint,
    long version
) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AcquiredDocument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(canonicalUri, "canonicalUri");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        Objects.requireNonNull(lastChangedAt, "lastChangedAt");
        validateFingerprint(contentFingerprint, "contentFingerprint", true);
        validateFingerprint(lastProcessedFingerprint, "lastProcessedFingerprint", false);
        if (consecutiveGoneCount < 0) throw new IllegalArgumentException("consecutiveGoneCount cannot be negative");
        if (version < 0) throw new IllegalArgumentException("version cannot be negative");
    }

    public AcquiredDocument processed(String fingerprint) {
        validateFingerprint(fingerprint, "fingerprint", true);
        return new AcquiredDocument(id, sourceId, canonicalUri, parentDocumentId, kind, mediaType,
            contentFingerprint, etag, lastModified, storageUri, state, firstSeenAt, lastSeenAt,
            lastChangedAt, lastGoneAt, consecutiveGoneCount, lastHttpStatus, fingerprint, version);
    }

    private static void validateFingerprint(String value, String field, boolean required) {
        if (value == null && !required) return;
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256 hex");
        }
    }

    public enum DocumentKind { ANNOUNCEMENT, ATTACHMENT }
    public enum DocumentState { ACTIVE, DEACTIVATED }
}
