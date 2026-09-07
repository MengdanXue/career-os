package com.careeros.infrastructure.persistence;

import static com.careeros.application.ExtractionPorts.*;
import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.careeros.application.AcquisitionHttpPorts.DiscoveredLink;
import com.careeros.application.AcquisitionHttpPorts.FetchedDocument;
import com.careeros.application.AcquisitionPorts.AcquisitionStore;
import com.careeros.application.AcquisitionService;
import com.careeros.application.ExtractionService;
import com.careeros.domain.*;
import com.careeros.domain.acquisition.AcquiredDocument;
import com.careeros.domain.acquisition.AcquiredDocument.DocumentKind;
import com.careeros.domain.acquisition.AcquiredDocument.DocumentState;
import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.acquisition.RecruitmentSource.CrawlMode;
import com.careeros.domain.acquisition.RecruitmentSource.SourceType;
import com.careeros.domain.acquisition.SourceCrawlRun.RunStatus;
import com.careeros.domain.acquisition.SourceCrawlRun.RunTrigger;
import com.careeros.infrastructure.acquisition.Phase2DocumentProcessor;
import com.careeros.infrastructure.extraction.JsoupDocumentParser;
import com.careeros.infrastructure.extraction.MediaTypeDocumentParser;
import com.careeros.infrastructure.extraction.NetworkntProposalValidator;
import com.careeros.infrastructure.extraction.PdfBoxDocumentParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GenericDocumentReprocessingTest {
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");
    private static final LocalDate DEADLINE = LocalDate.of(2026, 9, 30);
    private static final URI PAGE = URI.create("https://official.example/art/2026/notice");
    private static final String TEXT = "Engineer recruitment: one information systems position, age limit 35. "
        + "Applications close on 2026-09-30. This notice does not specify an age calculation date. "
        + "The candidate must provide original documentation for qualification review.";

    @ParameterizedTest(name = "cached {0}, HTTP {1}")
    @CsvSource({"text/html,200", "text/html,304", "application/pdf,200", "application/pdf,304"})
    void legacyGenericExtractionRunsCorrectedWriterAfterUnchangedAcquisition(String mediaType, int httpStatus)
        throws Exception {
        var fixture = new Fixture(mediaType, httpStatus);
        assertThat(fixture.job.ageReferenceDate).isEqualTo(DEADLINE);

        var reparsed = fixture.acquisition.run(fixture.source.id(), RunTrigger.MANUAL);
        var stable = fixture.acquisition.run(fixture.source.id(), RunTrigger.MANUAL);

        assertThat(reparsed.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(stable.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(reparsed.unchangedCount()).isEqualTo(1);
        assertThat(stable.unchangedCount()).isEqualTo(1);
        assertThat(fixture.job.ageReferenceDate).as("deadline is not evidence of an age reference date").isNull();
        assertThat(fixture.job.maximumAge).isEqualTo(35);
        assertThat(fixture.job.minimumExperienceYears).isNull();
        assertThat(fixture.job.contentFingerprint).isNotEqualTo("b".repeat(64));
        assertThat(fixture.extractionCalls.get()).isEqualTo(1);
        assertThat(fixture.extractionRuns).hasSize(2).containsValue(fixture.legacy);
        assertThat(fixture.event.evidenceIds).contains(fixture.legacy.run().evidenceId()).hasSize(2);
        assertThat(fixture.document.get().lastProcessorVersion()).isEqualTo(Phase2DocumentProcessor.PROCESSOR_VERSION);
        assertThat(fixture.document.get().lastHttpStatus()).isEqualTo(httpStatus);
    }

    private static final class Fixture {
        final RecruitmentSource source = new RecruitmentSource(UUID.randomUUID(), "GENERIC", "官方招聘",
            URI.create("https://official.example/"), URI.create("https://official.example/list"),
            SourceType.OFFICIAL_GOVERNMENT, "杭州", CrawlMode.STATIC_HTML, true,
            "0 0 8 * * *", "Asia/Shanghai", Duration.ZERO, Map.of(), null, null, NOW, 0, NOW, NOW);
        final JpaModels.JobPostingEntity job = new JpaModels.JobPostingEntity();
        final JpaModels.RecruitmentEventEntity event = new JpaModels.RecruitmentEventEntity();
        final Map<String, PersistedExtraction> extractionRuns = new LinkedHashMap<>();
        final AtomicInteger extractionCalls = new AtomicInteger();
        final AtomicReference<AcquiredDocument> document;
        final PersistedExtraction legacy;
        final AcquisitionService acquisition;

        Fixture(String mediaType, int httpStatus) throws Exception {
            byte[] content = mediaType.equals("text/html")
                ? ("<html><p>" + TEXT + "</p></html>").getBytes(StandardCharsets.UTF_8) : pdf();
            var artifacts = new MemoryArtifacts();
            var artifact = artifacts.put(content, mediaType, NOW);
            document = new AtomicReference<>(new AcquiredDocument(UUID.randomUUID(), source.id(), PAGE, null,
                DocumentKind.ANNOUNCEMENT, mediaType, artifact.sha256(), "legacy-etag", null,
                URI.create(artifact.storageUri()), DocumentState.ACTIVE, NOW, NOW, NOW, null, 0, 200,
                artifact.sha256(), "official-fact-fusion-v14", 0));
            var parser = new MediaTypeDocumentParser(List.of(new JsoupDocumentParser(), new PdfBoxDocumentParser()));
            var extractor = new StructuredExtractor() {
                public ExtractorDescriptor descriptor() {
                    return new ExtractorDescriptor("fixture", "1.0.0", "fixture-model", "p1", true);
                }
                public ExtractionAttempt extract(ParsedDocument parsed, ExtractionContext context) {
                    extractionCalls.incrementAndGet();
                    assertThat(parsed.fragments()).isNotEmpty();
                    return new ExtractionAttempt(proposal(context.evidence().id(), parsed.fragments().getFirst().id()), "{}");
                }
            };
            String oldIdentity = artifact.sha256()
                + "|media-type-router|1.0.0|fixture|1.0.0|fixture-model|p1|" + RecruitmentExtractionProposal.SCHEMA_VERSION;
            UUID oldEvidence = UUID.randomUUID();
            var legacyRun = new ExtractionRun(UUID.randomUUID(), oldEvidence, null, null,
                sha256(oldIdentity.getBytes(StandardCharsets.UTF_8)),
                mediaType.equals("text/html") ? ExtractionSourceType.HTML : ExtractionSourceType.PDF,
                mediaType.equals("text/html") ? "jsoup" : "pdfbox",
                mediaType.equals("text/html") ? "1.22.2" : "3.0.8",
                "fixture", "1.0.0", "fixture-model", "p1", RecruitmentExtractionProposal.SCHEMA_VERSION,
                DataQualityStatus.VERIFIED, 0.98, proposal(oldEvidence, UUID.randomUUID()), "{}", null, null, NOW, NOW);
            legacy = new PersistedExtraction(legacyRun, Optional.empty());
            extractionRuns.put(legacyRun.inputFingerprint(), legacy);
            var persistence = new ExtractionPersistence() {
                public Optional<PersistedExtraction> findByInputFingerprint(String fingerprint) {
                    return Optional.ofNullable(extractionRuns.get(fingerprint));
                }
                public PersistedExtraction save(ExtractionBundle bundle) {
                    var saved = new PersistedExtraction(bundle.run(), Optional.ofNullable(bundle.review()).map(ReviewItem::id));
                    extractionRuns.put(bundle.run().inputFingerprint(), saved);
                    return saved;
                }
                public PersistedExtraction saveFailure(FailedExtractionBundle bundle) {
                    throw new AssertionError("Unexpected extraction failure: " + bundle.run().errorMessage());
                }
                public PersistedExtraction findById(UUID id) {
                    return extractionRuns.values().stream().filter(value -> value.run().id().equals(id)).findFirst().orElseThrow();
                }
            };
            var organizations = mock(OrganizationJpaRepository.class);
            var organization = new JpaModels.OrganizationEntity();
            organization.id = UUID.randomUUID(); organization.name = "测试信息中心";
            when(organizations.findFirstByName(organization.name)).thenReturn(Optional.of(organization));
            var events = mock(RecruitmentEventJpaRepository.class);
            event.id = UUID.randomUUID(); event.sourceUrl = PAGE.toString();
            event.applicationEndsOn = DEADLINE; event.evidenceIds = List.of(oldEvidence);
            when(events.findFirstBySourceUrl(PAGE.toString())).thenReturn(Optional.of(event));
            when(events.save(any())).thenAnswer(call -> call.getArgument(0));
            var jobs = mock(JobPostingJpaRepository.class);
            job.id = UUID.randomUUID(); job.recruitmentEventId = event.id; job.organizationId = organization.id;
            job.ageReferenceDate = DEADLINE; job.contentFingerprint = "b".repeat(64);
            when(jobs.findByStableJobKey(any())).thenReturn(Optional.of(job));
            when(jobs.save(any())).thenAnswer(call -> call.getArgument(0));
            var writer = new DefaultJobUpsertService(jobs, events, organizations, Clock.fixed(NOW, ZoneOffset.UTC));
            UnitOfWork transactions = new UnitOfWork() {
                public <T> T execute(Supplier<T> operation) { return operation.get(); }
            };
            FingerprintLock lock = new FingerprintLock() {
                public <T> T execute(String fingerprint, Supplier<T> operation) { return operation.get(); }
            };
            // Deterministic model/evidence boundary; parser, schema, review policy and writer remain real.
            var extraction = new ExtractionService(artifacts, parser, (file, parsed) -> parsed, extractor,
                new NetworkntProposalValidator(), (proposal, fragments) -> List.of(), persistence, writer,
                transactions, lock, mock(ExtractionObserver.class), new ReviewPolicy(0.90),
                Clock.fixed(NOW, ZoneOffset.UTC), 1_000_000);
            var processor = new Phase2DocumentProcessor(extraction, mock(OfficialExcelImportService.class),
                new OfficialAnnouncementFactService(events));
            var store = mock(AcquisitionStore.class);
            when(store.findSource(source.id())).thenReturn(source);
            when(store.findDocument(source.id(), PAGE)).thenAnswer(call -> Optional.of(document.get()));
            when(store.saveDocument(any())).thenAnswer(call -> {
                document.set(call.getArgument(0));
                return document.get();
            });
            when(store.saveRun(any())).thenAnswer(call -> call.getArgument(0));
            acquisition = new AcquisitionService(store, (code, wait, operation) -> Optional.of(operation.get()),
                (configured, page, body) -> List.of(new DiscoveredLink(PAGE, "2026年公开招聘")),
                request -> request.uri().equals(PAGE)
                    ? new FetchedDocument(PAGE, httpStatus, httpStatus == 304 ? null : mediaType,
                        httpStatus == 304 ? new byte[0] : content, "legacy-etag", null)
                    : new FetchedDocument(request.uri(), 200, "text/html", "list".getBytes(StandardCharsets.UTF_8), null, null),
                (configured, page, body) -> List.of(), processor, artifacts,
                (configured, after) -> after.plus(Duration.ofDays(1)), Clock.fixed(NOW, ZoneOffset.UTC), 1_000_000);
        }
    }

    private static RecruitmentExtractionProposal proposal(UUID evidence, UUID fragment) {
        var source = new RecruitmentExtractionProposal.SourceProposal(evidence, PAGE.toString(), "2026年公开招聘");
        var organization = new RecruitmentExtractionProposal.OrganizationProposal("测试信息中心",
            explicit(OrganizationType.PUBLIC_INSTITUTION, fragment));
        var event = new RecruitmentExtractionProposal.EventProposal("2026年公开招聘", 2026,
            EventType.PUBLIC_INSTITUTION, unknown(), unknown(), explicit(DEADLINE, fragment));
        var job = new RecruitmentExtractionProposal.JobProposal(explicit("Engineer", fragment), "A01",
            explicit(1, fragment), explicit(EmploymentType.ESTABLISHMENT, fragment), "杭州",
            unknown(), unknown(), unknown(), explicit(35, fragment), unknown(), unknown(),
            JobFamily.INFORMATION_SYSTEMS, "Systems engineering");
        return new RecruitmentExtractionProposal(RecruitmentExtractionProposal.SCHEMA_VERSION,
            source, organization, event, List.of(job), List.of(), 0.98, false);
    }

    private static <T> ExtractedFact<T> explicit(T value, UUID fragment) {
        return new ExtractedFact<>(value, FactStatus.EXPLICIT, 0.98, List.of(fragment), null);
    }

    private static <T> ExtractedFact<T> unknown() {
        return new ExtractedFact<>(null, FactStatus.UNKNOWN, 0, List.of(), null);
    }

    private static byte[] pdf() throws Exception {
        try (var pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                stream.beginText(); stream.setFont(new PDType1Font(FontName.HELVETICA), 10);
                stream.newLineAtOffset(30, 700); stream.showText(TEXT); stream.endText();
            }
            pdf.save(bytes);
            return bytes.toByteArray();
        }
    }

    private static final class MemoryArtifacts implements ArtifactStore {
        private final Map<String, byte[]> content = new HashMap<>();
        public SourceArtifact put(byte[] bytes, String mediaType, Instant capturedAt) {
            String hash = sha256(bytes);
            String uri = "memory:/" + hash;
            content.put(uri, bytes.clone());
            return new SourceArtifact(UUID.randomUUID(), hash, mediaType, bytes.length, uri, capturedAt);
        }
        public InputStream open(SourceArtifact artifact) { return new ByteArrayInputStream(content.get(artifact.storageUri())); }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
