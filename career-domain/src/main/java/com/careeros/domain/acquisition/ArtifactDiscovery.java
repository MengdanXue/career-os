package com.careeros.domain.acquisition;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The durable inventory row for a discovered announcement or attachment.
 *
 * <p>An inventory row exists before a remote fetch is attempted. This keeps a
 * failed download or parse visible even when no {@link AcquiredDocument} can
 * be created.</p>
 */
public record ArtifactDiscovery(
    UUID id,
    UUID sourceId,
    UUID runId,
    UUID parentDocumentId,
    URI canonicalUri,
    URI fetchUri,
    String title,
    AcquiredDocument.DocumentKind kind,
    LocalDate publishedOn,
    DiscoveryStatus status,
    String errorCode,
    Instant firstSeenAt,
    Instant lastAttemptAt,
    int attemptCount
) {
    public ArtifactDiscovery {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(runId, "runId");
        requireUri(canonicalUri, "canonicalUri");
        requireUri(fetchUri, "fetchUri");
        if (canonicalUri.getUserInfo() != null || fetchUri.getUserInfo() != null) {
            throw new IllegalArgumentException("artifact URI must not contain credentials");
        }
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title must not be blank");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        Objects.requireNonNull(lastAttemptAt, "lastAttemptAt");
        if (lastAttemptAt.isBefore(firstSeenAt)) {
            throw new IllegalArgumentException("lastAttemptAt cannot precede firstSeenAt");
        }
        if (attemptCount < 1) throw new IllegalArgumentException("attemptCount must be positive");
        if (errorCode != null && errorCode.isBlank()) throw new IllegalArgumentException("errorCode must not be blank");
        if (status == DiscoveryStatus.PROCESSED && errorCode != null) {
            throw new IllegalArgumentException("processed discovery cannot have an error code");
        }
    }

    private static void requireUri(URI uri, String name) {
        Objects.requireNonNull(uri, name);
        if (!uri.isAbsolute() || uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException(name + " must be an absolute URI with a host");
        }
    }

    public boolean unresolved() {
        return status == DiscoveryStatus.DISCOVERED
            || status == DiscoveryStatus.FETCHED
            || status == DiscoveryStatus.FETCH_FAILED
            || status == DiscoveryStatus.PARSE_FAILED;
    }

    public enum DiscoveryStatus { DISCOVERED, FETCHED, FETCH_FAILED, PARSE_FAILED, PROCESSED }
}
