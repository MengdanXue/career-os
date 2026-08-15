package com.careeros.domain.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.domain.acquisition.AcquiredDocument.DocumentKind;
import com.careeros.domain.acquisition.AcquiredDocument.DocumentState;
import com.careeros.domain.acquisition.DocumentTransition.TransitionType;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentTransitionTest {
    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");
    private static final URI URI_VALUE = URI.create("https://hrss.hangzhou.gov.cn/art/notice.html");
    private static final String FIRST = "a".repeat(64);
    private static final String SECOND = "b".repeat(64);

    @Test
    void firstSuccessfulFetchIsAdded() {
        var result = DocumentTransition.decide(null, FetchObservation.ok(
            URI_VALUE, 200, FIRST, "text/html", "etag-1", null), NOW);

        assertThat(result.type()).isEqualTo(TransitionType.ADDED);
        assertThat(result.shouldProcess()).isTrue();
        assertThat(result.document().contentFingerprint()).isEqualTo(FIRST);
        assertThat(result.document().state()).isEqualTo(DocumentState.ACTIVE);
    }

    @Test
    void identicalFingerprintIsUnchangedAndNotProcessedAfterSuccess() {
        var prior = document(FIRST, FIRST, DocumentState.ACTIVE, 0, null);

        var result = DocumentTransition.decide(prior, FetchObservation.ok(
            URI_VALUE, 200, FIRST, "text/html", "etag-1", null), NOW);

        assertThat(result.type()).isEqualTo(TransitionType.UNCHANGED);
        assertThat(result.shouldProcess()).isFalse();
        assertThat(result.document().lastSeenAt()).isEqualTo(NOW);
    }

    @Test
    void notModifiedContentRetriesFailedProcessing() {
        var prior = document(FIRST, null, DocumentState.ACTIVE, 0, null);

        var result = DocumentTransition.decide(prior, FetchObservation.notModified(
            URI_VALUE, "etag-1", null), NOW);

        assertThat(result.type()).isEqualTo(TransitionType.UNCHANGED);
        assertThat(result.shouldProcess()).isTrue();
    }

    @Test
    void changedFingerprintIsUpdated() {
        var prior = document(FIRST, FIRST, DocumentState.ACTIVE, 0, null);

        var result = DocumentTransition.decide(prior, FetchObservation.ok(
            URI_VALUE, 200, SECOND, "text/html", "etag-2", null), NOW);

        assertThat(result.type()).isEqualTo(TransitionType.UPDATED);
        assertThat(result.previousFingerprint()).isEqualTo(FIRST);
        assertThat(result.currentFingerprint()).isEqualTo(SECOND);
        assertThat(result.shouldProcess()).isTrue();
    }

    @Test
    void oneGoneObservationDoesNotDeactivate() {
        var prior = document(FIRST, FIRST, DocumentState.ACTIVE, 0, null);

        var result = DocumentTransition.decide(prior, FetchObservation.gone(URI_VALUE, 404), NOW);

        assertThat(result.type()).isEqualTo(TransitionType.UNCHANGED);
        assertThat(result.document().consecutiveGoneCount()).isEqualTo(1);
        assertThat(result.document().state()).isEqualTo(DocumentState.ACTIVE);
    }

    @Test
    void secondGoneObservationSixHoursLaterDeactivatesExactlyOnce() {
        var prior = document(FIRST, FIRST, DocumentState.ACTIVE, 1, NOW.minus(Duration.ofHours(6)));

        var result = DocumentTransition.decide(prior, FetchObservation.gone(URI_VALUE, 410), NOW);

        assertThat(result.type()).isEqualTo(TransitionType.DEACTIVATED);
        assertThat(result.document().state()).isEqualTo(DocumentState.DEACTIVATED);
        assertThat(result.shouldProcess()).isFalse();
    }

    @Test
    void aRepeatedGoneObservationDoesNotDuplicateDeactivation() {
        var prior = document(FIRST, FIRST, DocumentState.DEACTIVATED, 2, NOW.minus(Duration.ofDays(1)));

        var result = DocumentTransition.decide(prior, FetchObservation.gone(URI_VALUE, 404), NOW);

        assertThat(result.type()).isEqualTo(TransitionType.UNCHANGED);
        assertThat(result.document().state()).isEqualTo(DocumentState.DEACTIVATED);
    }

    @Test
    void timeoutAndServerFailureNeverDeactivate() {
        var prior = document(FIRST, FIRST, DocumentState.ACTIVE, 1, NOW.minus(Duration.ofDays(1)));

        var timeout = DocumentTransition.decide(prior, FetchObservation.failure(URI_VALUE, 0), NOW);
        var serverError = DocumentTransition.decide(prior, FetchObservation.failure(URI_VALUE, 503), NOW);

        assertThat(timeout.type()).isEqualTo(TransitionType.NONE);
        assertThat(serverError.type()).isEqualTo(TransitionType.NONE);
        assertThat(timeout.document().state()).isEqualTo(DocumentState.ACTIVE);
    }

    @Test
    void returningDocumentReactivatesAsUpdatedEvenWithSameFingerprint() {
        var prior = document(FIRST, FIRST, DocumentState.DEACTIVATED, 2, NOW.minus(Duration.ofDays(1)));

        var result = DocumentTransition.decide(prior, FetchObservation.ok(
            URI_VALUE, 200, FIRST, "text/html", "etag-3", null), NOW);

        assertThat(result.type()).isEqualTo(TransitionType.UPDATED);
        assertThat(result.document().state()).isEqualTo(DocumentState.ACTIVE);
        assertThat(result.shouldProcess()).isTrue();
    }

    private static AcquiredDocument document(
        String fingerprint,
        String processedFingerprint,
        DocumentState state,
        int goneCount,
        Instant lastGoneAt
    ) {
        return new AcquiredDocument(
            UUID.fromString("01992f09-0000-7000-8000-000000000401"),
            UUID.fromString("01992f09-0000-7000-8000-000000000301"),
            URI_VALUE,
            null,
            DocumentKind.ANNOUNCEMENT,
            "text/html",
            fingerprint,
            "etag-1",
            null,
            URI.create("file:///artifact"),
            state,
            NOW.minus(Duration.ofDays(2)),
            NOW.minus(Duration.ofDays(1)),
            NOW.minus(Duration.ofDays(1)),
            lastGoneAt,
            goneCount,
            200,
            processedFingerprint,
            0
        );
    }
}
