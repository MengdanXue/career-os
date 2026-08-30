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
    String listingMetadataFingerprint,
    String etag,
    String lastModified,
    URI storageUri,
    TransportRisk transportRisk,
    DocumentState state,
    Instant firstSeenAt,
    Instant lastSeenAt,
    Instant lastChangedAt,
    Instant lastGoneAt,
    int consecutiveGoneCount,
    int lastHttpStatus,
    String lastProcessedFingerprint,
    String lastProcessorVersion,
    long version
) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AcquiredDocument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(canonicalUri, "canonicalUri");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(transportRisk, "transportRisk");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        Objects.requireNonNull(lastChangedAt, "lastChangedAt");
        validateFingerprint(contentFingerprint, "contentFingerprint", true);
        validateFingerprint(listingMetadataFingerprint, "listingMetadataFingerprint", false);
        validateFingerprint(lastProcessedFingerprint, "lastProcessedFingerprint", false);
        if (lastProcessorVersion != null && lastProcessorVersion.isBlank()) {
            throw new IllegalArgumentException("lastProcessorVersion must not be blank");
        }
        if (consecutiveGoneCount < 0) throw new IllegalArgumentException("consecutiveGoneCount cannot be negative");
        if (version < 0) throw new IllegalArgumentException("version cannot be negative");
    }

    public AcquiredDocument(
        UUID id, UUID sourceId, URI canonicalUri, UUID parentDocumentId, DocumentKind kind,
        String mediaType, String contentFingerprint, String etag, String lastModified,
        URI storageUri, TransportRisk transportRisk, DocumentState state, Instant firstSeenAt,
        Instant lastSeenAt, Instant lastChangedAt, Instant lastGoneAt, int consecutiveGoneCount,
        int lastHttpStatus, String lastProcessedFingerprint, String lastProcessorVersion, long version
    ) {
        this(id, sourceId, canonicalUri, parentDocumentId, kind, mediaType, contentFingerprint,
            null, etag, lastModified, storageUri, transportRisk, state, firstSeenAt, lastSeenAt,
            lastChangedAt, lastGoneAt, consecutiveGoneCount, lastHttpStatus,
            lastProcessedFingerprint, lastProcessorVersion, version);
    }

    public AcquiredDocument(
        UUID id, UUID sourceId, URI canonicalUri, UUID parentDocumentId, DocumentKind kind,
        String mediaType, String contentFingerprint, String etag, String lastModified,
        URI storageUri, DocumentState state, Instant firstSeenAt, Instant lastSeenAt,
        Instant lastChangedAt, Instant lastGoneAt, int consecutiveGoneCount, int lastHttpStatus,
        String lastProcessedFingerprint, String lastProcessorVersion, long version
    ) {
        this(id, sourceId, canonicalUri, parentDocumentId, kind, mediaType, contentFingerprint,
            null, etag, lastModified, storageUri, transportRiskFor(canonicalUri), state, firstSeenAt,
            lastSeenAt, lastChangedAt, lastGoneAt, consecutiveGoneCount, lastHttpStatus,
            lastProcessedFingerprint, lastProcessorVersion, version);
    }

    public AcquiredDocument(
        UUID id, UUID sourceId, URI canonicalUri, UUID parentDocumentId, DocumentKind kind,
        String mediaType, String contentFingerprint, String etag, String lastModified,
        URI storageUri, DocumentState state, Instant firstSeenAt, Instant lastSeenAt,
        Instant lastChangedAt, Instant lastGoneAt, int consecutiveGoneCount, int lastHttpStatus,
        String lastProcessedFingerprint, long version
    ) {
        this(id, sourceId, canonicalUri, parentDocumentId, kind, mediaType, contentFingerprint,
            etag, lastModified, storageUri, state, firstSeenAt, lastSeenAt, lastChangedAt,
            lastGoneAt, consecutiveGoneCount, lastHttpStatus, lastProcessedFingerprint, null, version);
    }

    public AcquiredDocument processed(String fingerprint, String processorVersion) {
        validateFingerprint(fingerprint, "fingerprint", true);
        if (processorVersion == null || processorVersion.isBlank()) {
            throw new IllegalArgumentException("processorVersion is required");
        }
        return new AcquiredDocument(id, sourceId, canonicalUri, parentDocumentId, kind, mediaType,
            contentFingerprint, listingMetadataFingerprint, etag, lastModified, storageUri,
            transportRisk, state, firstSeenAt, lastSeenAt,
            lastChangedAt, lastGoneAt, consecutiveGoneCount, lastHttpStatus, fingerprint, processorVersion, version);
    }

    public AcquiredDocument withListingMetadataFingerprint(String fingerprint, Instant changedAt) {
        validateFingerprint(fingerprint, "fingerprint", false);
        return new AcquiredDocument(id, sourceId, canonicalUri, parentDocumentId, kind, mediaType,
            contentFingerprint, fingerprint, etag, lastModified, storageUri, transportRisk, state,
            firstSeenAt, lastSeenAt, changedAt, lastGoneAt, consecutiveGoneCount, lastHttpStatus,
            lastProcessedFingerprint, lastProcessorVersion, version);
    }

    private static TransportRisk transportRiskFor(URI canonicalUri) {
        return canonicalUri != null && "http".equalsIgnoreCase(canonicalUri.getScheme())
            ? TransportRisk.PLAINTEXT_OFFICIAL_HTTP : TransportRisk.NONE;
    }

    private static void validateFingerprint(String value, String field, boolean required) {
        if (value == null && !required) return;
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256 hex");
        }
    }

    public enum DocumentKind { ANNOUNCEMENT, ATTACHMENT }
    public enum DocumentState { ACTIVE, DEACTIVATED }
    public enum TransportRisk { NONE, PLAINTEXT_OFFICIAL_HTTP }
}
