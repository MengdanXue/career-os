package com.careeros.domain.acquisition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArtifactDiscoveryTest {
    private static final UUID ID = UUID.randomUUID();
    private static final UUID SOURCE = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");

    @Test
    void allowsInventoryBeforeARealDocumentExists() {
        var value = new ArtifactDiscovery(ID, SOURCE, RUN, null,
            URI.create("https://example.test/jobs/1"), URI.create("https://example.test/jobs/1"),
            "Recruitment announcement", AcquiredDocument.DocumentKind.ANNOUNCEMENT,
            LocalDate.of(2026, 4, 1), ArtifactDiscovery.DiscoveryStatus.FETCH_FAILED,
            "HTTP_429", NOW, NOW, 1);

        assertThat(value.unresolved()).isTrue();
        assertThat(new ArtifactDiscovery(ID, SOURCE, RUN, null,
            URI.create("https://example.test/jobs/1"), URI.create("https://example.test/jobs/1"),
            "Recruitment announcement", AcquiredDocument.DocumentKind.ANNOUNCEMENT,
            null, ArtifactDiscovery.DiscoveryStatus.FETCHED, null, NOW, NOW, 1).unresolved()).isTrue();
    }

    @Test
    void rejectsCredentialBearingOrRelativeUris() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ArtifactDiscovery(ID, SOURCE, RUN, null,
            URI.create("https://user:secret@example.test/jobs/1"), URI.create("https://example.test/jobs/1"),
            "announcement", AcquiredDocument.DocumentKind.ANNOUNCEMENT, null,
            ArtifactDiscovery.DiscoveryStatus.DISCOVERED, null, NOW, NOW, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new ArtifactDiscovery(ID, SOURCE, RUN, null,
            URI.create("/jobs/1"), URI.create("https://example.test/jobs/1"),
            "announcement", AcquiredDocument.DocumentKind.ANNOUNCEMENT, null,
            ArtifactDiscovery.DiscoveryStatus.DISCOVERED, null, NOW, NOW, 1));
    }

    @Test
    void validatesOptionalRawFetchMetadata() {
        var value = new ArtifactDiscovery(ID, SOURCE, RUN, null,
            URI.create("https://example.test/jobs/1"), URI.create("https://example.test/jobs/1"),
            "Recruitment announcement", AcquiredDocument.DocumentKind.ANNOUNCEMENT,
            null, ArtifactDiscovery.DiscoveryStatus.FETCHED, null, NOW, NOW, 1,
            "text/html", "a".repeat(64), 42);

        assertThat(value.mediaType()).isEqualTo("text/html");
        assertThat(value.rawChecksum()).isEqualTo("a".repeat(64));
        assertThat(value.sizeBytes()).isEqualTo(42);
        assertThatIllegalArgumentException().isThrownBy(() -> new ArtifactDiscovery(ID, SOURCE, RUN, null,
            URI.create("https://example.test/jobs/1"), URI.create("https://example.test/jobs/1"),
            "Recruitment announcement", AcquiredDocument.DocumentKind.ANNOUNCEMENT,
            null, ArtifactDiscovery.DiscoveryStatus.FETCHED, null, NOW, NOW, 1,
            "text/html", "not-a-checksum", 42));
    }

    @Test
    void processedRowsCannotCarryAnError() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ArtifactDiscovery(ID, SOURCE, RUN, null,
            URI.create("https://example.test/jobs/1"), URI.create("https://example.test/jobs/1"),
            "announcement", AcquiredDocument.DocumentKind.ANNOUNCEMENT, null,
            ArtifactDiscovery.DiscoveryStatus.PROCESSED, "PARSE_WARN", NOW, NOW, 1));
    }
}
