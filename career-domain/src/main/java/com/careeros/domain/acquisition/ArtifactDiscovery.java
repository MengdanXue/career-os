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
    int attemptCount,
    String mediaType,
    String rawChecksum,
    long sizeBytes,
    Classification classification
) {
    private static final java.util.regex.Pattern SHA256 =
        java.util.regex.Pattern.compile("[0-9a-f]{64}");

    public ArtifactDiscovery(
        UUID id, UUID sourceId, UUID runId, UUID parentDocumentId,
        URI canonicalUri, URI fetchUri, String title, AcquiredDocument.DocumentKind kind,
        LocalDate publishedOn, DiscoveryStatus status, String errorCode,
        Instant firstSeenAt, Instant lastAttemptAt, int attemptCount
    ) {
        this(id, sourceId, runId, parentDocumentId, canonicalUri, fetchUri, title, kind,
            publishedOn, status, errorCode, firstSeenAt, lastAttemptAt, attemptCount,
            null, null, 0, Classification.UNKNOWN);
    }

    public ArtifactDiscovery(
        UUID id, UUID sourceId, UUID runId, UUID parentDocumentId,
        URI canonicalUri, URI fetchUri, String title, AcquiredDocument.DocumentKind kind,
        LocalDate publishedOn, DiscoveryStatus status, String errorCode,
        Instant firstSeenAt, Instant lastAttemptAt, int attemptCount,
        String mediaType, String rawChecksum, long sizeBytes
    ) {
        this(id, sourceId, runId, parentDocumentId, canonicalUri, fetchUri, title, kind,
            publishedOn, status, errorCode, firstSeenAt, lastAttemptAt, attemptCount,
            mediaType, rawChecksum, sizeBytes, Classification.UNKNOWN);
    }

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
        Objects.requireNonNull(classification, "classification");
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
        if (mediaType != null && mediaType.isBlank()) {
            throw new IllegalArgumentException("mediaType must not be blank");
        }
        if (rawChecksum != null && !SHA256.matcher(rawChecksum).matches()) {
            throw new IllegalArgumentException("rawChecksum must be lowercase SHA-256 hex");
        }
        if (sizeBytes < 0) throw new IllegalArgumentException("sizeBytes cannot be negative");
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

    public enum Classification {
        ANNOUNCEMENT,
        JOB_TABLE,
        MAJOR_CATALOG,
        REGISTRATION_FORM,
        RESULTS_OR_LIFECYCLE,
        UNKNOWN
    }

    /**
     * Classifies the document family from stable official metadata only.
     * Ambiguous names deliberately remain UNKNOWN so they cannot inflate job counts.
     */
    public static Classification classify(
        String title, URI uri, AcquiredDocument.DocumentKind kind
    ) {
        Objects.requireNonNull(kind, "kind");
        if (kind == AcquiredDocument.DocumentKind.ANNOUNCEMENT) return Classification.ANNOUNCEMENT;
        String value = ((title == null ? "" : title) + " " + (uri == null ? "" : uri))
            .toLowerCase(java.util.Locale.ROOT);
        if (containsAny(value, "报名", "应聘", "申请表", "登记表")) {
            return Classification.REGISTRATION_FORM;
        }
        if (containsAny(value, "专业目录", "学科目录", "专业代码", "目录及代码")) {
            return Classification.MAJOR_CATALOG;
        }
        if (containsAny(value, "面试名单", "体检名单", "考察名单", "拟聘", "公示名单", "成绩名单", "笔试成绩")) {
            return Classification.RESULTS_OR_LIFECYCLE;
        }
        if (containsAny(value, "岗位表", "岗位计划", "招聘计划", "职位表", "岗位需求", "招聘岗位")) {
            return Classification.JOB_TABLE;
        }
        return Classification.UNKNOWN;
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }
}
