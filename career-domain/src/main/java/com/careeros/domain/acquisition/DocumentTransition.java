package com.careeros.domain.acquisition;

import static com.careeros.domain.acquisition.AcquiredDocument.DocumentState.ACTIVE;
import static com.careeros.domain.acquisition.AcquiredDocument.DocumentState.DEACTIVATED;

import com.careeros.domain.acquisition.AcquiredDocument.DocumentKind;
import com.careeros.domain.acquisition.AcquiredDocument.TransportRisk;
import com.careeros.domain.acquisition.FetchObservation.ObservationType;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DocumentTransition(
    TransitionType type,
    AcquiredDocument document,
    boolean shouldProcess,
    String previousFingerprint,
    String currentFingerprint
) {
    public DocumentTransition {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(document, "document");
    }

    public static DocumentTransition decide(
        AcquiredDocument previous,
        FetchObservation observation,
        Instant now
    ) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(now, "now");
        if (previous == null) {
            if (observation.type() != ObservationType.OK) {
                throw new IllegalArgumentException("A new document requires successful content");
            }
            var added = new AcquiredDocument(
                UUID.randomUUID(), UUID.randomUUID(), observation.uri(), null, DocumentKind.ANNOUNCEMENT,
                observation.mediaType(), observation.contentFingerprint(), observation.etag(), observation.lastModified(),
                URI.create("memory:/pending"), observation.transportRisk(), ACTIVE, now, now, now, null, 0,
                observation.status(), null, null, 0);
            return new DocumentTransition(TransitionType.ADDED, added, true, null, added.contentFingerprint());
        }
        if (!previous.canonicalUri().equals(observation.uri())) {
            throw new IllegalArgumentException("Observation URI does not match document URI");
        }
        if (observation.type() == ObservationType.FAILURE) {
            return new DocumentTransition(TransitionType.NONE, previous, false,
                previous.contentFingerprint(), previous.contentFingerprint());
        }
        if (observation.type() == ObservationType.GONE) {
            int goneCount = previous.consecutiveGoneCount() + 1;
            boolean separated = previous.lastGoneAt() != null
                && Duration.between(previous.lastGoneAt(), now).compareTo(Duration.ofHours(6)) >= 0;
            boolean deactivate = previous.state() == ACTIVE && goneCount >= 2 && separated;
            var gone = copy(previous, previous.mediaType(), previous.contentFingerprint(), previous.etag(),
                previous.lastModified(), previous.storageUri(), deactivate ? DEACTIVATED : previous.state(),
                now, deactivate ? now : previous.lastChangedAt(), now, goneCount, observation.status(),
                mergeRisk(previous.transportRisk(), observation.transportRisk()));
            return new DocumentTransition(deactivate ? TransitionType.DEACTIVATED : TransitionType.UNCHANGED,
                gone, false, previous.contentFingerprint(), previous.contentFingerprint());
        }
        String fingerprint = observation.type() == ObservationType.NOT_MODIFIED
            ? previous.contentFingerprint() : observation.contentFingerprint();
        boolean reactivated = previous.state() == DEACTIVATED;
        boolean changed = !fingerprint.equals(previous.contentFingerprint()) || reactivated;
        var success = copy(previous,
            observation.mediaType() == null ? previous.mediaType() : observation.mediaType(),
            fingerprint,
            observation.etag() == null ? previous.etag() : observation.etag(),
            observation.lastModified() == null ? previous.lastModified() : observation.lastModified(),
            previous.storageUri(), ACTIVE, now, changed ? now : previous.lastChangedAt(), null, 0,
            observation.status(), mergeRisk(previous.transportRisk(), observation.transportRisk()));
        boolean retryProcessing = !fingerprint.equals(previous.lastProcessedFingerprint());
        return new DocumentTransition(changed ? TransitionType.UPDATED : TransitionType.UNCHANGED,
            success, changed || retryProcessing, previous.contentFingerprint(), fingerprint);
    }

    public AcquiredDocument bind(UUID sourceId, UUID parentDocumentId, DocumentKind kind, URI storageUri) {
        return new AcquiredDocument(document.id(), sourceId, document.canonicalUri(), parentDocumentId, kind,
            document.mediaType(), document.contentFingerprint(), document.etag(), document.lastModified(), storageUri,
            document.transportRisk(), document.state(), document.firstSeenAt(), document.lastSeenAt(), document.lastChangedAt(),
            document.lastGoneAt(), document.consecutiveGoneCount(), document.lastHttpStatus(),
            document.lastProcessedFingerprint(), document.lastProcessorVersion(), document.version());
    }

    private static AcquiredDocument copy(
        AcquiredDocument value, String mediaType, String fingerprint, String etag, String lastModified,
        URI storageUri, AcquiredDocument.DocumentState state, Instant seen, Instant changed, Instant gone,
        int goneCount, int status, TransportRisk transportRisk
    ) {
        return new AcquiredDocument(value.id(), value.sourceId(), value.canonicalUri(), value.parentDocumentId(),
            value.kind(), mediaType, fingerprint, etag, lastModified, storageUri, transportRisk, state, value.firstSeenAt(), seen,
            changed, gone, goneCount, status, value.lastProcessedFingerprint(), value.lastProcessorVersion(),
            value.version());
    }

    private static TransportRisk mergeRisk(TransportRisk previous, TransportRisk observed) {
        return previous == TransportRisk.PLAINTEXT_OFFICIAL_HTTP
            || observed == TransportRisk.PLAINTEXT_OFFICIAL_HTTP
            ? TransportRisk.PLAINTEXT_OFFICIAL_HTTP : TransportRisk.NONE;
    }

    public enum TransitionType { ADDED, UPDATED, UNCHANGED, DEACTIVATED, NONE }
}
