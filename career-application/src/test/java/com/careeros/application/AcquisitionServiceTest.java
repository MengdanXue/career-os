package com.careeros.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.AcquiredDocumentProcessor.ProcessDocumentCommand;
import com.careeros.application.AcquiredDocumentProcessor.ProcessingResult;
import com.careeros.application.AcquisitionHttpPorts.DiscoveredLink;
import com.careeros.application.AcquisitionHttpPorts.FetchRequest;
import com.careeros.application.AcquisitionHttpPorts.FetchedDocument;
import com.careeros.application.AcquisitionPorts.*;
import com.careeros.application.ExtractionPorts.ArtifactStore;
import com.careeros.domain.DomainEnums.EventType;
import com.careeros.domain.SourceArtifact;
import com.careeros.domain.acquisition.*;
import com.careeros.domain.acquisition.AcquisitionChange.ChangeType;
import com.careeros.domain.acquisition.RecruitmentSource.CrawlMode;
import com.careeros.domain.acquisition.RecruitmentSource.SourceType;
import com.careeros.domain.acquisition.SourceCrawlRun.RunStatus;
import com.careeros.domain.acquisition.SourceCrawlRun.RunTrigger;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class AcquisitionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");
    private static final UUID SOURCE_ID = UUID.fromString("01992f09-0000-7000-8000-000000000301");
    private static final URI LIST = URI.create("https://official.example/list.html");
    private static final URI LIST_API = URI.create("https://official.example/api/list?page=1");
    private static final URI DETAIL = URI.create("https://official.example/art/2026/notice.html");

    @Test
    void identicalSecondRunDoesNotCreateAnotherChangeOrProcessingCall() {
        Fixture fixture = new Fixture();

        SourceCrawlRun first = fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);
        SourceCrawlRun second = fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);

        assertThat(first.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(second.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(fixture.store.changes).extracting(AcquisitionChange::changeType)
            .containsExactly(ChangeType.ADDED);
        assertThat(fixture.processor.calls).isEqualTo(1);
        assertThat(second.unchangedCount()).isEqualTo(1);
    }

    @Test
    void changedBodyCreatesOneUpdateAndProcessesTheNewFingerprint() {
        Fixture fixture = new Fixture();
        fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);
        fixture.fetcher.detail = "<html>第二版招聘公告</html>".getBytes(StandardCharsets.UTF_8);

        SourceCrawlRun changed = fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);

        assertThat(changed.updatedCount()).isEqualTo(1);
        assertThat(fixture.store.changes).extracting(AcquisitionChange::changeType)
            .containsExactly(ChangeType.ADDED, ChangeType.UPDATED);
        assertThat(fixture.processor.calls).isEqualTo(2);
    }

    @Test
    void failedProcessingRetriesOnUnchangedFetchWithoutDuplicatingChange() {
        Fixture fixture = new Fixture();
        fixture.processor.failNext = true;
        SourceCrawlRun failed = fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);

        SourceCrawlRun retried = fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);

        assertThat(failed.status()).isEqualTo(RunStatus.PARTIALLY_SUCCEEDED);
        assertThat(retried.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(fixture.store.changes).hasSize(1);
        assertThat(fixture.processor.calls).isEqualTo(2);
        assertThat(fixture.store.documents.values().iterator().next().lastProcessedFingerprint()).isNotNull();
    }

    @Test
    void redirectTargetDoesNotReplaceTheStableDiscoveredDocumentKey() {
        Fixture fixture = new Fixture();
        fixture.fetcher.finalDetailUri = URI.create("https://cdn.official.example/generated/notice.html");

        fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);
        SourceCrawlRun second = fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);

        assertThat(fixture.store.documents).containsOnlyKeys(DETAIL);
        assertThat(fixture.store.changes).extracting(AcquisitionChange::changeType)
            .containsExactly(ChangeType.ADDED);
        assertThat(second.unchangedCount()).isEqualTo(1);
    }

    @Test
    void usesConfiguredDeterministicListingApiInsteadOfEmptyJcmsShell() {
        Fixture fixture = new Fixture();

        fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);

        assertThat(fixture.fetcher.requested).startsWith(LIST_API);
    }

    private static final class Fixture {
        final InMemoryStore store = new InMemoryStore(source());
        final FakeFetcher fetcher = new FakeFetcher();
        final FakeProcessor processor = new FakeProcessor();
        final MemoryArtifacts artifacts = new MemoryArtifacts();
        final AcquisitionService service = new AcquisitionService(store,
            (code, wait, work) -> Optional.of(work.get()),
            (source, page, html) -> List.of(new DiscoveredLink(DETAIL, "2026年公开招聘公告")),
            fetcher,
            (source, page, html) -> List.of(), processor, artifacts,
            (source, after) -> after.plus(Duration.ofDays(1)), Clock.fixed(NOW, ZoneOffset.UTC),
            26_214_400);
    }

    private static RecruitmentSource source() {
        return new RecruitmentSource(SOURCE_ID, "OFFICIAL", "官方事业单位招聘",
            URI.create("https://official.example/"), LIST, SourceType.OFFICIAL_GOVERNMENT,
            "杭州", CrawlMode.STATIC_HTML, true, "0 0 8 * * *", "Asia/Shanghai",
            Duration.ZERO, Map.of("listingApiUri", LIST_API.toString()), null, null, NOW, 0, NOW, NOW);
    }

    private static final class FakeFetcher implements AcquisitionHttpPorts.DocumentFetcher {
        URI finalDetailUri = DETAIL;
        final List<URI> requested = new ArrayList<>();
        byte[] detail = "<html>第一版招聘公告</html>".getBytes(StandardCharsets.UTF_8);
        @Override public FetchedDocument fetch(FetchRequest request) {
            requested.add(request.uri());
            if (request.uri().equals(LIST) || request.uri().equals(LIST_API)) return new FetchedDocument(request.uri(), 200, "text/html",
                "<html>list</html>".getBytes(StandardCharsets.UTF_8), null, null);
            return new FetchedDocument(finalDetailUri, 200, "text/html", detail, null, null);
        }
    }

    private static final class FakeProcessor implements AcquiredDocumentProcessor {
        int calls;
        boolean failNext;
        @Override public ProcessingResult process(ProcessDocumentCommand command) {
            calls++;
            if (failNext) { failNext = false; return ProcessingResult.failed("TEST_FAILURE"); }
            return ProcessingResult.extracted(UUID.nameUUIDFromBytes(command.content()));
        }
    }

    private static final class MemoryArtifacts implements ArtifactStore {
        final Map<String, byte[]> values = new HashMap<>();
        @Override public SourceArtifact put(byte[] content, String mediaType, Instant capturedAt) {
            String fingerprint = sha256(content);
            String uri = "memory:/" + fingerprint;
            values.put(uri, content.clone());
            return new SourceArtifact(UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.US_ASCII)),
                fingerprint, mediaType, content.length, uri, capturedAt);
        }
        @Override public InputStream open(SourceArtifact artifact) {
            return new ByteArrayInputStream(values.get(artifact.storageUri()));
        }
    }

    private static final class InMemoryStore implements AcquisitionStore {
        RecruitmentSource source;
        final Map<URI, AcquiredDocument> documents = new LinkedHashMap<>();
        final Map<UUID, SourceCrawlRun> runs = new LinkedHashMap<>();
        final List<AcquisitionChange> changes = new ArrayList<>();
        InMemoryStore(RecruitmentSource source) { this.source = source; }
        @Override public List<RecruitmentSource> findDueSources(Instant now, int limit) { return List.of(source); }
        @Override public List<RecruitmentSource> findSources() { return List.of(source); }
        @Override public RecruitmentSource findSource(UUID id) { return source; }
        @Override public RecruitmentSource saveSource(RecruitmentSource value) { source=value; return value; }
        @Override public SourceCrawlRun saveRun(SourceCrawlRun run) { runs.put(run.id(), run); return run; }
        @Override public SourceCrawlRun findRun(UUID id) { return runs.get(id); }
        @Override public RunPage findRuns(RunQuery query, int page, int size) { return new RunPage(List.copyOf(runs.values()),page,size,runs.size()); }
        @Override public Optional<AcquiredDocument> findDocument(UUID sourceId, URI uri) { return Optional.ofNullable(documents.get(uri)); }
        @Override public List<AcquiredDocument> findDocuments(UUID sourceId) { return List.copyOf(documents.values()); }
        @Override public AcquiredDocument saveDocument(AcquiredDocument value) { documents.put(value.canonicalUri(),value); return value; }
        @Override public AcquisitionChange appendChange(AcquisitionChange value) { changes.add(value); return value; }
        @Override public PersistedDocumentChange saveDocumentAndChange(AcquiredDocument document, AcquisitionChange change) {
            saveDocument(document); appendChange(change); return new PersistedDocumentChange(document, change);
        }
        @Override public ChangePage findChanges(ChangeCursor cursor, UUID sourceId, Set<ChangeType> types, int size) {
            return new ChangePage(List.copyOf(changes), null);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
