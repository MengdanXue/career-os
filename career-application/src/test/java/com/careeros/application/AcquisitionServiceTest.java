package com.careeros.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.AcquiredDocumentProcessor.ProcessDocumentCommand;
import com.careeros.application.AcquiredDocumentProcessor.ProcessingResult;
import com.careeros.application.AcquisitionHttpPorts.DiscoveredLink;
import com.careeros.application.AcquisitionHttpPorts.FetchRequest;
import com.careeros.application.AcquisitionHttpPorts.FetchedDocument;
import com.careeros.application.AcquisitionHttpPorts.HttpReadContract;
import com.careeros.application.AcquisitionHttpPorts.ListingResult;
import com.careeros.application.AcquisitionHttpPorts.TransportPolicy;
import com.careeros.application.AcquisitionHttpPorts.TransportRisk;
import com.careeros.application.AcquisitionHttpPorts.YearDiscoveredLink;
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
    private static final URI ATTACHMENT = URI.create("https://official.example/document/download?fileName=jobs.xlsx&fileUrl=token%2Fvalue%3D");

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
    void incrementalRunRecordsObservedYearWithoutClaimingHistoricalCompleteness() {
        Fixture fixture = new Fixture();
        fixture.store.targetJobs.put(2026, 15L);

        fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);

        SourceYearCoverage coverage = fixture.store.coverages.get(2026);
        assertThat(coverage.status()).isEqualTo(SourceYearCoverage.CoverageStatus.PARTIAL);
        assertThat(coverage.discoveredCount()).isEqualTo(1);
        assertThat(coverage.fetchedCount()).isEqualTo(1);
        assertThat(coverage.parsedCount()).isEqualTo(1);
        assertThat(coverage.targetJobCount()).isEqualTo(15);
        assertThat(coverage.supportsAbsenceConclusion()).isFalse();
        assertThat(coverage.stopReason()).isEqualTo("INCREMENTAL_WINDOW_ONLY");
    }

    @Test
    void incrementalCoverageRefreshesTargetJobCountAfterReclassification() {
        Fixture fixture = new Fixture();
        fixture.store.targetJobs.put(2026, 5L);
        fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);

        fixture.store.targetJobs.put(2026, 0L);
        fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);

        SourceYearCoverage coverage = fixture.store.coverages.get(2026);
        assertThat(coverage.discoveredCount()).isEqualTo(1);
        assertThat(coverage.fetchedCount()).isEqualTo(1);
        assertThat(coverage.parsedCount()).isEqualTo(1);
        assertThat(coverage.targetJobCount()).isZero();
        assertThat(coverage.stopReason()).isEqualTo("INCREMENTAL_WINDOW_ONLY");
    }

    @Test
    void incrementalObservationDoesNotDowngradeConclusiveHistoricalCoverage() {
        Fixture fixture = historicalFixture();
        fixture.store.targetJobs.put(2026, 2L);
        fixture.service.backfill(SOURCE_ID, Set.of(2026));
        SourceYearCoverage verified = fixture.store.coverages.get(2026);

        fixture.fetcher.historicalListing = false;
        fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);

        assertThat(fixture.store.coverages.get(2026)).isEqualTo(verified);
        assertThat(verified.supportsAbsenceConclusion()).isTrue();
    }

    @Test
    void unchangedDocumentIsReprocessedOnceWhenProcessorVersionChanges() {
        Fixture fixture = new Fixture();
        fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);
        fixture.processor.version = "official-facts-v2";

        SourceCrawlRun reprocessed = fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);
        SourceCrawlRun stable = fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);

        assertThat(reprocessed.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(fixture.processor.calls).isEqualTo(2);
        assertThat(fixture.store.changes).hasSize(1);
        assertThat(fixture.store.documents.values().iterator().next().lastProcessorVersion())
            .isEqualTo("official-facts-v2");
        assertThat(stable.unchangedCount()).isEqualTo(1);
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
    void fetchedTransportRiskReachesTheStoredDocumentWithoutUriInference() {
        Fixture fixture = new Fixture();
        fixture.fetcher.detailTransportRisk = TransportRisk.PLAINTEXT_OFFICIAL_HTTP;

        fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);

        assertThat(fixture.store.documents.get(DETAIL).transportRisk())
            .isEqualTo(AcquiredDocument.TransportRisk.PLAINTEXT_OFFICIAL_HTTP);
    }

    @Test
    void incrementalRunUsesBoundedListingReaderWhenTheSourceRequiresMultiPageDiscovery() {
        RecruitmentSource source = sourceWithBoundedIncrementalListing();
        InMemoryStore store = new InMemoryStore(source);
        FakeFetcher fetcher = new FakeFetcher();
        FakeDiscoverer discoverer = new FakeDiscoverer();
        FakeAttachmentDiscoverer attachments = new FakeAttachmentDiscoverer();
        FakeProcessor processor = new FakeProcessor();
        MemoryArtifacts artifacts = new MemoryArtifacts();
        List<AcquisitionHttpPorts.ListingQuery> queries = new ArrayList<>();
        AcquisitionHttpPorts.SourceListingReader reader = (value, query) -> {
            queries.add(query);
            return new ListingResult(
                List.of(new YearDiscoveredLink(new DiscoveredLink(DETAIL, "2026年公开招聘公告"), 2026)),
                Map.of());
        };
        AcquisitionService service = new AcquisitionService(store,
            (code, wait, work) -> Optional.of(work.get()), discoverer, reader, fetcher, attachments,
            processor, artifacts, (value, after) -> after.plus(Duration.ofDays(1)),
            AcquisitionObserver.NOOP, Clock.fixed(NOW, ZoneOffset.UTC), 26_214_400);

        SourceCrawlRun run = service.run(SOURCE_ID, RunTrigger.MANUAL);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(queries).singleElement().satisfies(query -> assertThat(query.historical()).isFalse());
        assertThat(fetcher.requested).containsExactly(DETAIL);
    }

    @Test
    void nonJcmsIncrementalRunAlwaysDelegatesToTheListingReader() {
        RecruitmentSource source = sourceWithoutJcmsConfiguration();
        InMemoryStore store = new InMemoryStore(source);
        FakeFetcher fetcher = new FakeFetcher();
        List<AcquisitionHttpPorts.ListingQuery> queries = new ArrayList<>();
        AcquisitionHttpPorts.SourceListingReader reader = (value, query) -> {
            queries.add(query);
            return new ListingResult(
                List.of(new YearDiscoveredLink(new DiscoveredLink(DETAIL, "2026年公开招聘公告"), 2026)),
                Map.of());
        };
        AcquisitionService service = new AcquisitionService(store,
            (code, wait, work) -> Optional.of(work.get()), new FakeDiscoverer(), reader, fetcher,
            new FakeAttachmentDiscoverer(), new FakeProcessor(), new MemoryArtifacts(),
            (value, after) -> after.plus(Duration.ofDays(1)), AcquisitionObserver.NOOP,
            Clock.fixed(NOW, ZoneOffset.UTC), 26_214_400);

        SourceCrawlRun run = service.run(SOURCE_ID, RunTrigger.MANUAL);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(queries).containsExactly(new AcquisitionHttpPorts.ListingQuery(Set.of(), false));
        assertThat(fetcher.requested).containsExactly(DETAIL);
    }

    @Test
    void auditedHttpListingContractIsReusedForTheDiscoveredDetailFetch() {
        URI detail = URI.create("http://legacy.example/public/jobs/art/2026/notice.html");
        var contract = new HttpReadContract(TransportPolicy.AUDITED_HTTP_READ_ONLY,
            Set.of("legacy.example"), Set.of("/public/jobs"));
        InMemoryStore store = new InMemoryStore(sourceWithoutJcmsConfiguration());
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.finalDetailUri = detail;
        AcquisitionHttpPorts.SourceListingReader reader = (value, query) -> new ListingResult(
            List.of(new YearDiscoveredLink(
                new DiscoveredLink(detail, "2026年公开招聘公告", contract), 2026)), Map.of());
        AcquisitionService service = new AcquisitionService(store,
            (code, wait, work) -> Optional.of(work.get()), new FakeDiscoverer(), reader, fetcher,
            new FakeAttachmentDiscoverer(), new FakeProcessor(), new MemoryArtifacts(),
            (value, after) -> after.plus(Duration.ofDays(1)), AcquisitionObserver.NOOP,
            Clock.fixed(NOW, ZoneOffset.UTC), 26_214_400);

        SourceCrawlRun run = service.run(SOURCE_ID, RunTrigger.MANUAL);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(fetcher.fetchRequests).singleElement().satisfies(request -> {
            assertThat(request.uri()).isEqualTo(detail);
            assertThat(request.readContract()).isEqualTo(contract);
        });
    }

    @Test
    void auditedHttpDetailContractFlowsThroughAttachmentDiscoveryAndFetch() {
        URI detail = URI.create("http://legacy.example/public/jobs/art/2026/notice.html");
        URI attachment = URI.create("http://legacy.example/public/jobs/files/plan.xlsx");
        var contract = new HttpReadContract(TransportPolicy.AUDITED_HTTP_READ_ONLY,
            Set.of("legacy.example"), Set.of("/public/jobs"));
        InMemoryStore store = new InMemoryStore(sourceWithoutJcmsConfiguration());
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.finalDetailUri = detail;
        FakeAttachmentDiscoverer attachments = new FakeAttachmentDiscoverer();
        attachments.links = List.of(new DiscoveredLink(attachment, "岗位表", contract));
        AcquisitionHttpPorts.SourceListingReader reader = (value, query) -> new ListingResult(
            List.of(new YearDiscoveredLink(
                new DiscoveredLink(detail, "2026年公开招聘公告", contract), 2026)), Map.of());
        AcquisitionService service = new AcquisitionService(store,
            (code, wait, work) -> Optional.of(work.get()), new FakeDiscoverer(), reader, fetcher,
            attachments, new FakeProcessor(), new MemoryArtifacts(),
            (value, after) -> after.plus(Duration.ofDays(1)), AcquisitionObserver.NOOP,
            Clock.fixed(NOW, ZoneOffset.UTC), 26_214_400);

        SourceCrawlRun run = service.run(SOURCE_ID, RunTrigger.MANUAL);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(attachments.parent).isEqualTo(new DiscoveredLink(
            detail, "2026年公开招聘公告", contract));
        assertThat(fetcher.fetchRequests).extracting(FetchRequest::uri)
            .containsExactly(detail, attachment);
        assertThat(fetcher.fetchRequests.get(1).readContract()).isEqualTo(contract);
    }

    @Test
    void discoversNewAttachmentRulesFromStoredHtmlAfterNotModifiedResponse() {
        Fixture fixture = new Fixture();
        fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);
        fixture.attachments.links = List.of(new DiscoveredLink(ATTACHMENT, "招聘计划表"));
        fixture.fetcher.detailNotModified = true;

        SourceCrawlRun rerun = fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);

        assertThat(rerun.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(fixture.fetcher.requested).contains(ATTACHMENT);
        assertThat(fixture.store.documents).containsKeys(DETAIL, ATTACHMENT);
    }

    @Test
    void discoversNewAttachmentRulesFromStoredXhtmlAfterNotModifiedResponse() {
        Fixture fixture = new Fixture();
        fixture.fetcher.detailMediaType = "application/xhtml+xml";
        fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);
        fixture.attachments.links = List.of(new DiscoveredLink(ATTACHMENT, "招聘计划表"));
        fixture.fetcher.detailNotModified = true;

        SourceCrawlRun rerun = fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);

        assertThat(rerun.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(fixture.fetcher.requested).contains(ATTACHMENT);
        assertThat(fixture.store.documents).containsKeys(DETAIL, ATTACHMENT);
    }

    @Test
    void attachmentProcessingKeepsTheParentAnnouncementTitle() {
        Fixture fixture = new Fixture();
        fixture.attachments.links = List.of(new DiscoveredLink(ATTACHMENT, "招聘计划表.xlsx"));

        fixture.service.run(SOURCE_ID, RunTrigger.MANUAL);

        ProcessDocumentCommand attachment = fixture.processor.commands.stream()
            .filter(command -> command.documentUri().equals(ATTACHMENT))
            .findFirst().orElseThrow();
        assertThat(attachment.announcementTitle()).isEqualTo("2026年公开招聘公告");
    }

    @Test
    void historicalBackfillTraversesReportedPagesAndSelectsOnlyRequestedYears() {
        Fixture fixture = new Fixture();
        URI detail2024 = URI.create("https://official.example/art/2024/notice-a.html");
        URI detail2025 = URI.create("https://official.example/art/2025/notice-b.html");
        URI detail2026 = URI.create("https://official.example/art/2026/notice-c.html");
        URI detail2023 = URI.create("https://official.example/art/2023/notice-d.html");
        fixture.fetcher.historicalListing = true;
        fixture.fetcher.historicalListingTotal = 4;
        fixture.discoverer.pages.put("page-1", List.of(
            new DiscoveredLink(detail2026, "2026年公开招聘公告"),
            new DiscoveredLink(detail2025, "2025年公开招聘公告")));
        fixture.discoverer.pages.put("page-2", List.of(
            new DiscoveredLink(detail2024, "公开招聘公告"),
            new DiscoveredLink(detail2023, "2023年公开招聘公告")));

        SourceCrawlRun run = fixture.service.backfill(SOURCE_ID, Set.of(2024, 2025, 2026));

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(fixture.reader.queries)
            .containsExactly(new AcquisitionHttpPorts.ListingQuery(Set.of(2024, 2025, 2026), true));
        assertThat(fixture.store.documents).containsKeys(detail2024, detail2025, detail2026)
            .doesNotContainKey(detail2023);
    }

    @Test
    void legacySinglePageHistoricalFallbackCannotClaimCompleteCoverage() {
        RecruitmentSource source = sourceWithoutJcmsConfiguration();
        InMemoryStore store = new InMemoryStore(source);
        FakeFetcher fetcher = new FakeFetcher();
        AcquisitionService service = new AcquisitionService(store,
            (code, wait, work) -> Optional.of(work.get()), new FakeDiscoverer(), fetcher,
            new FakeAttachmentDiscoverer(), new FakeProcessor(), new MemoryArtifacts(),
            (value, after) -> after.plus(Duration.ofDays(1)), AcquisitionObserver.NOOP,
            Clock.fixed(NOW, ZoneOffset.UTC), 26_214_400);

        SourceCrawlRun run = service.backfill(SOURCE_ID, Set.of(2026));

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(store.coverages.get(2026).status())
            .isEqualTo(SourceYearCoverage.CoverageStatus.PARTIAL);
        assertThat(store.coverages.get(2026).supportsAbsenceConclusion()).isFalse();
        assertThat(store.coverages.get(2026).completionBasis()).isNull();
        assertThat(store.coverages.get(2026).completedAt()).isNull();
    }

    @Test
    void historicalBackfillDoesNotTreatDiscoveredButUnmatchedDocumentsAsNoTargetRecords() {
        Fixture fixture = historicalFixture();
        fixture.store.targetJobs.put(2024, 3L);
        fixture.store.targetJobs.put(2025, 0L);
        fixture.store.targetJobs.put(2026, 2L);

        fixture.service.backfill(SOURCE_ID, Set.of(2024, 2025, 2026));

        assertThat(fixture.store.coverages.values())
            .extracting(SourceYearCoverage::recruitmentYear, SourceYearCoverage::status,
                SourceYearCoverage::targetJobCount, SourceYearCoverage::supportsAbsenceConclusion)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(2024, SourceYearCoverage.CoverageStatus.COMPLETE, 3, true),
                org.assertj.core.groups.Tuple.tuple(2025, SourceYearCoverage.CoverageStatus.PARTIAL, 0, false),
                org.assertj.core.groups.Tuple.tuple(2026, SourceYearCoverage.CoverageStatus.COMPLETE, 2, true));
        assertThat(fixture.store.coverages.get(2025).completionBasis()).isNull();
        assertThat(fixture.store.coverages.get(2025).completedAt()).isNull();
        assertThat(fixture.store.coverages.get(2024).completionBasis()).contains("official listing total=3");
        assertThat(fixture.store.coverages.get(2026).completedAt()).isEqualTo(NOW);
    }

    @Test
    void historicalBackfillUsesNoTargetRecordsOnlyWhenTheCompleteIndexHasNoCandidateAnnouncement() {
        Fixture fixture = historicalFixture();

        fixture.service.backfill(SOURCE_ID, Set.of(2023));

        SourceYearCoverage coverage = fixture.store.coverages.get(2023);
        assertThat(coverage.status()).isEqualTo(SourceYearCoverage.CoverageStatus.NO_TARGET_RECORDS);
        assertThat(coverage.discoveredCount()).isZero();
        assertThat(coverage.supportsAbsenceConclusion()).isTrue();
    }

    @Test
    void historicalBackfillKeepsDocumentFailureAsPartialInsteadOfZeroJobs() {
        Fixture fixture = historicalFixture();
        URI failed = URI.create("https://official.example/art/2025/notice-b.html");
        fixture.fetcher.failedUris.add(failed);

        fixture.service.backfill(SOURCE_ID, Set.of(2025));

        SourceYearCoverage coverage = fixture.store.coverages.get(2025);
        assertThat(coverage.status()).isEqualTo(SourceYearCoverage.CoverageStatus.PARTIAL);
        assertThat(coverage.completedAt()).isNull();
        assertThat(coverage.supportsAbsenceConclusion()).isFalse();
        assertThat(fixture.store.source.consecutiveFailureCount()).isEqualTo(1);
    }

    @Test
    void historicalBackfillRecordsListingFailureAsAccessFailed() {
        Fixture fixture = historicalFixture();
        fixture.fetcher.historicalListingStatus = 503;

        fixture.service.backfill(SOURCE_ID, Set.of(2024, 2025));

        assertThat(fixture.store.coverages.values())
            .extracting(SourceYearCoverage::recruitmentYear, SourceYearCoverage::status)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(2024, SourceYearCoverage.CoverageStatus.ACCESS_FAILED),
                org.assertj.core.groups.Tuple.tuple(2025, SourceYearCoverage.CoverageStatus.ACCESS_FAILED));
        assertThat(fixture.store.coverages.values()).allSatisfy(coverage ->
            assertThat(coverage.supportsAbsenceConclusion()).isFalse());
        assertThat(fixture.store.importFailures).singleElement().satisfies(failure ->
            assertThat(failure.stage()).isEqualTo(
                ArtifactImportFailure.FailureStage.REMOTE_ACCESS_FAILED));
    }

    @Test
    void oneRequestedYearCannotClaimTheThreeYearBackfillCheckpoint() {
        Fixture fixture = historicalFixture();
        fixture.store.targetJobs.put(2026, 1L);

        fixture.service.backfill(SOURCE_ID, Set.of(2026));

        assertThat(fixture.store.coverages.get(2026).supportsAbsenceConclusion()).isTrue();
        assertThat(fixture.store.checkpoints).doesNotContainKey(
            com.careeros.domain.acquisition.SourceOnboardingCheckpoint.Checkpoint.BACKFILL_COMPLETE);
    }

    @Test
    void failedBackfillRetainsLastConclusiveAnnualEvidence() {
        Fixture fixture = historicalFixture();
        fixture.store.targetJobs.put(2025, 1L);
        fixture.service.backfill(SOURCE_ID, Set.of(2025));
        SourceYearCoverage verified = fixture.store.coverages.get(2025);
        fixture.fetcher.historicalListingStatus = 503;

        fixture.service.backfill(SOURCE_ID, Set.of(2025));

        assertThat(fixture.store.coverages.get(2025)).isEqualTo(verified);
        assertThat(verified.status()).isEqualTo(SourceYearCoverage.CoverageStatus.COMPLETE);
        assertThat(verified.supportsAbsenceConclusion()).isTrue();
    }

    @Test
    void historicalBackfillKeepsRowLevelProcessingErrorsPartialAndRetryable() {
        Fixture fixture = historicalFixture();
        fixture.store.targetJobs.put(2025, 1L);
        fixture.processor.rowErrors = true;

        SourceCrawlRun first = fixture.service.backfill(SOURCE_ID, Set.of(2025));
        SourceCrawlRun second = fixture.service.backfill(SOURCE_ID, Set.of(2025));

        assertThat(first.status()).isEqualTo(RunStatus.PARTIALLY_SUCCEEDED);
        assertThat(second.status()).isEqualTo(RunStatus.PARTIALLY_SUCCEEDED);
        assertThat(fixture.store.source.consecutiveFailureCount()).isZero();
        assertThat(fixture.store.coverages.get(2025).status())
            .isEqualTo(SourceYearCoverage.CoverageStatus.PARTIAL);
        assertThat(fixture.processor.calls).isEqualTo(2);
        assertThat(fixture.store.importFailures).hasSize(2)
            .allSatisfy(failure -> {
                assertThat(failure.stage()).isEqualTo(ArtifactImportFailure.FailureStage.ROW_PARSE_FAILED);
                assertThat(failure.rowNumber()).isEqualTo(3);
            });
        assertThat(fixture.store.documents.values()).allSatisfy(document -> {
            assertThat(document.lastProcessedFingerprint()).isEqualTo(document.contentFingerprint());
            assertThat(document.lastProcessorVersion()).endsWith(":partial");
        });
    }

    @Test
    void historicalBackfillRejectsRepeatedNonTerminalListingPages() {
        Fixture fixture = historicalFixture();
        fixture.discoverer.pages.put("page-2", fixture.discoverer.pages.get("page-1"));

        SourceCrawlRun run = fixture.service.backfill(SOURCE_ID, Set.of(2024, 2025, 2026));

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(fixture.store.coverages.values()).allSatisfy(coverage ->
            assertThat(coverage.status()).isEqualTo(SourceYearCoverage.CoverageStatus.ACCESS_FAILED));
    }

    @Test
    void historicalBackfillReconcilesOfficialTotalBeforeApplyingCandidateTitleFilters() {
        Fixture fixture = new Fixture();
        fixture.fetcher.historicalListing = true;
        fixture.fetcher.historicalListingTotal = 2;
        DiscoveredLink candidate = new DiscoveredLink(
            URI.create("https://official.example/art/2026/notice-a.html"), "2026年公开招聘公告");
        DiscoveredLink excluded = new DiscoveredLink(
            URI.create("https://official.example/art/2026/result-b.html"), "2026年拟聘人员公示");
        fixture.discoverer.pages.put("page-1", List.of(candidate));
        fixture.discoverer.rawPages.put("page-1", List.of(candidate, excluded));

        SourceCrawlRun run = fixture.service.backfill(SOURCE_ID, Set.of(2026));

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.discoveredCount()).isEqualTo(1);
        assertThat(fixture.fetcher.requested).contains(candidate.uri());
        assertThat(fixture.fetcher.requested).doesNotContain(excluded.uri());
    }

    @Test
    void connectionLifecycleRequiresBackfillThenIdempotentIncrementalRun() {
        Fixture fixture = historicalFixture();
        fixture.store.targetJobs.put(2024, 1L);
        fixture.store.targetJobs.put(2025, 1L);
        fixture.store.targetJobs.put(2026, 1L);
        fixture.discoverer.pages.put("<html>list</html>", List.of(
            new DiscoveredLink(URI.create("https://official.example/art/2026/notice-c.html"),
                "2026年公开招聘公告")));

        fixture.service.backfill(SOURCE_ID, Set.of(2024, 2025, 2026));

        assertThat(fixture.store.checkpoints.keySet())
            .contains(com.careeros.domain.acquisition.SourceOnboardingCheckpoint.Checkpoint.BACKFILL_COMPLETE)
            .doesNotContain(com.careeros.domain.acquisition.SourceOnboardingCheckpoint.Checkpoint.INCREMENTAL_VERIFIED);

        fixture.fetcher.historicalListing = false;
        fixture.service.run(SOURCE_ID, RunTrigger.SCHEDULED);

        assertThat(fixture.store.checkpoints.keySet())
            .contains(com.careeros.domain.acquisition.SourceOnboardingCheckpoint.Checkpoint.INCREMENTAL_VERIFIED);
        assertThat(fixture.store.checkpoints.get(
            com.careeros.domain.acquisition.SourceOnboardingCheckpoint.Checkpoint.INCREMENTAL_VERIFIED).verifiedAt())
            .isAfterOrEqualTo(fixture.store.checkpoints.get(
                com.careeros.domain.acquisition.SourceOnboardingCheckpoint.Checkpoint.BACKFILL_COMPLETE).verifiedAt());
    }

    @Test
    void zeroCandidateIncrementalListingFailsTheDiscoveryContract() {
        Fixture fixture = new Fixture();
        fixture.discoverer.pages.put("<html>list</html>", List.of());

        SourceCrawlRun run = fixture.service.run(SOURCE_ID, RunTrigger.SCHEDULED);

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(fixture.store.checkpoints.keySet())
            .doesNotContain(com.careeros.domain.acquisition.SourceOnboardingCheckpoint.Checkpoint.INCREMENTAL_VERIFIED);
        assertThat(fixture.store.importFailures).singleElement().satisfies(failure ->
            assertThat(failure.stage()).isEqualTo(
                ArtifactImportFailure.FailureStage.DISCOVERY_CONTRACT_CHANGED));
    }

    @Test
    void responseReadIoFailureIsClassifiedAsRemoteAccessFailure() {
        Fixture fixture = new Fixture();
        fixture.fetcher.listFailure = new AcquisitionHttpPorts.FetchFailedException(
            "Could not read HTTP response", new java.io.IOException("connection reset"));

        SourceCrawlRun run = fixture.service.run(SOURCE_ID, RunTrigger.SCHEDULED);

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(fixture.store.importFailures).singleElement().satisfies(failure ->
            assertThat(failure.stage()).isEqualTo(
                ArtifactImportFailure.FailureStage.REMOTE_ACCESS_FAILED));
    }

    @Test
    void laterRemoteFailureDoesNotEraseAPreviouslyVerifiedLiveCheckpoint() {
        Fixture fixture = new Fixture();
        fixture.service.run(SOURCE_ID, RunTrigger.SCHEDULED);
        var checkpoint = fixture.store.checkpoints.get(
            com.careeros.domain.acquisition.SourceOnboardingCheckpoint.Checkpoint.LIVE_SMOKE_VERIFIED);
        fixture.fetcher.listFailure = new AcquisitionHttpPorts.FetchFailedException(
            "Could not read HTTP response", new java.io.IOException("connection reset"));

        SourceCrawlRun failed = fixture.service.run(SOURCE_ID, RunTrigger.SCHEDULED);

        assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
        assertThat(fixture.store.checkpoints.get(
            com.careeros.domain.acquisition.SourceOnboardingCheckpoint.Checkpoint.LIVE_SMOKE_VERIFIED))
            .isEqualTo(checkpoint);
    }

    private static Fixture historicalFixture() {
        Fixture fixture = new Fixture();
        fixture.fetcher.historicalListing = true;
        fixture.discoverer.pages.put("page-1", List.of(
            new DiscoveredLink(URI.create("https://official.example/art/2026/notice-c.html"), "2026年公开招聘公告"),
            new DiscoveredLink(URI.create("https://official.example/art/2025/notice-b.html"), "2025年公开招聘公告")));
        fixture.discoverer.pages.put("page-2", List.of(
            new DiscoveredLink(URI.create("https://official.example/art/2024/notice-a.html"), "2024年公开招聘公告")));
        return fixture;
    }

    private static final class Fixture {
        final InMemoryStore store;
        final FakeFetcher fetcher = new FakeFetcher();
        final FakeDiscoverer discoverer = new FakeDiscoverer();
        final FakeListingReader reader = new FakeListingReader(fetcher, discoverer);
        final FakeAttachmentDiscoverer attachments = new FakeAttachmentDiscoverer();
        final FakeProcessor processor = new FakeProcessor();
        final MemoryArtifacts artifacts = new MemoryArtifacts();
        final AcquisitionService service;

        Fixture() {
            this(source(), Clock.fixed(NOW, ZoneOffset.UTC));
        }

        Fixture(RecruitmentSource source, Clock clock) {
            store = new InMemoryStore(source);
            service = new AcquisitionService(store,
                (code, wait, work) -> Optional.of(work.get()), discoverer, reader, fetcher, attachments,
                processor, artifacts, (value, after) -> after.plus(Duration.ofDays(1)),
                AcquisitionObserver.NOOP, clock, 26_214_400);
        }
    }

    private static RecruitmentSource source() {
        return source(Duration.ZERO);
    }

    private static RecruitmentSource source(Duration minimumRequestInterval) {
        return new RecruitmentSource(SOURCE_ID, "OFFICIAL", "官方事业单位招聘",
            URI.create("https://official.example/"), LIST, SourceType.OFFICIAL_GOVERNMENT,
            "杭州", CrawlMode.STATIC_HTML, true, "0 0 8 * * *", "Asia/Shanghai",
            minimumRequestInterval, Map.of(
                "listingApiUri", LIST_API.toString(),
                "historicalPaginationMode", "JCMS_PARAM_JSON",
                "historicalPageSize", 2,
                "historicalMaxPages", 10), null, null, NOW, 0, NOW, NOW);
    }

    private static RecruitmentSource sourceWithBoundedIncrementalListing() {
        RecruitmentSource value = source();
        Map<String, Object> configuration = new LinkedHashMap<>(value.configuration());
        configuration.put("incrementalListingMaxPages", 10);
        return new RecruitmentSource(value.id(), value.code(), value.name(), value.baseUri(), value.entryUri(),
            value.sourceType(), value.region(), value.crawlMode(), value.enabled(), value.cronExpression(),
            value.timeZone(), value.minimumRequestInterval(), configuration, value.lastSuccessAt(),
            value.lastFailureAt(), value.nextDueAt(), value.consecutiveFailureCount(),
            value.createdAt(), value.updatedAt());
    }

    private static RecruitmentSource sourceWithoutJcmsConfiguration() {
        RecruitmentSource value = source();
        Map<String, Object> configuration = new LinkedHashMap<>(value.configuration());
        configuration.remove("historicalPaginationMode");
        configuration.remove("historicalPageSize");
        configuration.remove("historicalMaxPages");
        return new RecruitmentSource(value.id(), value.code(), value.name(), value.baseUri(), value.entryUri(),
            value.sourceType(), value.region(), value.crawlMode(), value.enabled(), value.cronExpression(),
            value.timeZone(), value.minimumRequestInterval(), configuration, value.lastSuccessAt(),
            value.lastFailureAt(), value.nextDueAt(), value.consecutiveFailureCount(),
            value.createdAt(), value.updatedAt());
    }

    private static final class FakeDiscoverer implements AcquisitionHttpPorts.SourceDiscoverer {
        final Map<String, List<DiscoveredLink>> pages = new HashMap<>();
        final Map<String, List<DiscoveredLink>> rawPages = new HashMap<>();
        @Override public List<DiscoveredLink> discover(RecruitmentSource source, URI pageUri, byte[] html) {
            String marker = new String(html, StandardCharsets.UTF_8);
            return pages.entrySet().stream().filter(entry -> marker.startsWith(entry.getKey()))
                .map(Map.Entry::getValue).findFirst()
                .orElse(List.of(new DiscoveredLink(DETAIL, "2026年公开招聘公告")));
        }
        @Override public List<DiscoveredLink> discoverAll(RecruitmentSource source, URI pageUri, byte[] html) {
            String marker = new String(html, StandardCharsets.UTF_8);
            return rawPages.entrySet().stream().filter(entry -> marker.startsWith(entry.getKey()))
                .map(Map.Entry::getValue).findFirst().orElseGet(() -> discover(source, pageUri, html));
        }
    }

    private static final class FakeFetcher implements AcquisitionHttpPorts.DocumentFetcher {
        URI finalDetailUri = DETAIL;
        final List<URI> requested = new ArrayList<>();
        final List<FetchRequest> fetchRequests = new ArrayList<>();
        byte[] detail = "<html>第一版招聘公告</html>".getBytes(StandardCharsets.UTF_8);
        String detailMediaType = "text/html";
        TransportRisk detailTransportRisk = TransportRisk.NONE;
        boolean detailNotModified;
        boolean historicalListing;
        int historicalListingStatus = 200;
        int historicalListingTotal = 3;
        RuntimeException listFailure;
        final Set<URI> failedUris = new HashSet<>();
        @Override public FetchedDocument fetch(FetchRequest request) {
            requested.add(request.uri());
            fetchRequests.add(request);
            if (listFailure != null && (request.uri().equals(LIST) || request.uri().equals(LIST_API))) {
                throw listFailure;
            }
            if (historicalListing && request.uri().getPath().equals("/api/list")) {
                int page = request.uri().getRawQuery().contains("pageNo%22%3A2") ? 2 : 1;
                byte[] content = historicalListingStatus == 200
                    ? ("page-" + page + " count=\\\"" + historicalListingTotal + "\\\"").getBytes(StandardCharsets.UTF_8)
                    : new byte[0];
                return new FetchedDocument(request.uri(), historicalListingStatus, "application/json",
                    content, null, null);
            }
            if (failedUris.contains(request.uri())) throw new AcquisitionHttpPorts.FetchFailedException("test failure");
            if (request.uri().equals(LIST) || request.uri().equals(LIST_API)) return new FetchedDocument(request.uri(), 200, "text/html",
                "<html>list</html>".getBytes(StandardCharsets.UTF_8), null, null);
            if (request.uri().equals(DETAIL) && detailNotModified) return new FetchedDocument(DETAIL, 304, null, new byte[0], null, null);
            if (request.uri().equals(ATTACHMENT)) return new FetchedDocument(ATTACHMENT, 200,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "workbook".getBytes(StandardCharsets.UTF_8), null, null);
            return new FetchedDocument(finalDetailUri, 200, detailMediaType, detail, null, null,
                detailTransportRisk);
        }
    }

    private static final class FakeListingReader implements AcquisitionHttpPorts.SourceListingReader {
        private final FakeFetcher fetcher;
        private final FakeDiscoverer discoverer;
        private final List<AcquisitionHttpPorts.ListingQuery> queries = new ArrayList<>();

        private FakeListingReader(FakeFetcher fetcher, FakeDiscoverer discoverer) {
            this.fetcher = fetcher;
            this.discoverer = discoverer;
        }

        @Override public ListingResult read(RecruitmentSource source, AcquisitionHttpPorts.ListingQuery query) {
            queries.add(query);
            if (fetcher.listFailure != null) throw fetcher.listFailure;
            if (!query.historical()) {
                List<YearDiscoveredLink> links = discoverer.discover(source, LIST_API,
                    "<html>list</html>".getBytes(StandardCharsets.UTF_8)).stream()
                    .map(link -> new YearDiscoveredLink(link, year(link)))
                    .toList();
                return new ListingResult(links, Map.of());
            }
            if (fetcher.historicalListingStatus != 200) {
                throw new AcquisitionHttpPorts.FetchFailedException(
                    "Historical list page did not return content: " + fetcher.historicalListingStatus);
            }
            List<Map.Entry<String, List<DiscoveredLink>>> pages = discoverer.pages.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("page-"))
                .sorted(Map.Entry.comparingByKey())
                .toList();
            LinkedHashMap<URI, DiscoveredLink> candidates = new LinkedHashMap<>();
            LinkedHashMap<URI, DiscoveredLink> raw = new LinkedHashMap<>();
            for (Map.Entry<String, List<DiscoveredLink>> page : pages) {
                int before = raw.size();
                List<DiscoveredLink> rawPage = discoverer.rawPages.getOrDefault(page.getKey(), page.getValue());
                rawPage.forEach(link -> raw.putIfAbsent(link.uri(), link));
                page.getValue().forEach(link -> candidates.putIfAbsent(link.uri(), link));
                if (before > 0 && raw.size() == before && before < fetcher.historicalListingTotal) {
                    throw new AcquisitionHttpPorts.FetchFailedException(
                        "Historical listing repeated a non-terminal page");
                }
            }
            if (raw.size() != fetcher.historicalListingTotal) {
                throw new AcquisitionHttpPorts.FetchFailedException(
                    "Historical listing unique entry count did not match reported total");
            }
            List<YearDiscoveredLink> accepted = candidates.values().stream()
                .map(link -> new YearDiscoveredLink(link, year(link)))
                .filter(link -> query.recruitmentYears().contains(link.recruitmentYear()))
                .toList();
            Map<Integer, AcquisitionHttpPorts.ListingEvidence> evidence = new LinkedHashMap<>();
            int pageCount = Math.max(1, pages.size());
            String basis = "official listing total=" + fetcher.historicalListingTotal
                + "; traversed pages=" + pageCount;
            for (int recruitmentYear : query.recruitmentYears()) {
                int annual = (int) accepted.stream()
                    .filter(link -> link.recruitmentYear() == recruitmentYear).count();
                evidence.put(recruitmentYear, new AcquisitionHttpPorts.ListingEvidence(
                    pageCount, fetcher.historicalListingTotal, annual,
                    Math.max(0, raw.size() - candidates.size()), 0, null, null,
                    true, "REPORTED_TOTAL_REACHED", basis));
            }
            return new ListingResult(accepted, evidence);
        }

        private static int year(DiscoveredLink link) {
            var path = java.util.regex.Pattern.compile("/(20\\d{2})(?:/|$)").matcher(link.uri().getPath());
            if (path.find()) return Integer.parseInt(path.group(1));
            var title = java.util.regex.Pattern.compile("(20\\d{2})").matcher(link.title());
            if (title.find()) return Integer.parseInt(title.group(1));
            throw new IllegalArgumentException("Test listing link has no recruitment year: " + link.uri());
        }
    }

    private static final class FakeAttachmentDiscoverer implements AcquisitionHttpPorts.AttachmentDiscoverer {
        List<DiscoveredLink> links = List.of();
        DiscoveredLink parent;
        @Override public List<DiscoveredLink> discover(RecruitmentSource source, URI pageUri, byte[] html) { return links; }
        @Override public List<DiscoveredLink> discover(
            RecruitmentSource source, DiscoveredLink page, byte[] html
        ) {
            parent = page;
            return links;
        }
    }

    private static final class FakeProcessor implements AcquiredDocumentProcessor {
        int calls;
        boolean failNext;
        boolean rowErrors;
        String version = "official-facts-v1";
        final List<ProcessDocumentCommand> commands = new ArrayList<>();
        @Override public String version() { return version; }
        @Override public ProcessingResult process(ProcessDocumentCommand command) {
            calls++;
            commands.add(command);
            if (failNext) { failNext = false; return ProcessingResult.failed("TEST_FAILURE"); }
            if (rowErrors) return ProcessingResult.importedWithErrors(
                UUID.nameUUIDFromBytes(command.content()), 1, 0, 0, 0,
                List.of(new AcquiredDocumentProcessor.ProcessingIssue(
                    ArtifactImportFailure.FailureStage.ROW_PARSE_FAILED,
                    "岗位计划", 3, "MISSING_ORGANIZATION", "招聘单位为空")));
            return ProcessingResult.extracted(UUID.nameUUIDFromBytes(command.content()));
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
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
        final Map<Integer, SourceYearCoverage> coverages = new LinkedHashMap<>();
        final Map<Integer, Long> targetJobs = new HashMap<>();
        final List<ArtifactImportFailure> importFailures = new ArrayList<>();
        final Map<com.careeros.domain.acquisition.SourceOnboardingCheckpoint.Checkpoint,
            com.careeros.domain.acquisition.SourceOnboardingCheckpoint> checkpoints = new EnumMap<>(
                com.careeros.domain.acquisition.SourceOnboardingCheckpoint.Checkpoint.class);
        InMemoryStore(RecruitmentSource source) { this.source = source; }
        @Override public List<RecruitmentSource> findDueSources(Instant now, int limit) { return List.of(source); }
        @Override public List<RecruitmentSource> findSources() { return List.of(source); }
        @Override public RecruitmentSource findSource(UUID id) { return source; }
        @Override public RecruitmentSource saveSource(RecruitmentSource value) { source=value; return value; }
        @Override public SourceCrawlRun saveRun(SourceCrawlRun run) { runs.put(run.id(), run); return run; }
        @Override public SourceCrawlRun findRun(UUID id) { return runs.get(id); }
        @Override public Optional<SourceCrawlRun> findLatestRun(UUID sourceId) {
            return runs.values().stream().max(java.util.Comparator.comparing(SourceCrawlRun::startedAt));
        }
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
        @Override public List<com.careeros.domain.acquisition.SourceYearCoverage> findSourceYearCoverage(
            UUID sourceId, Integer recruitmentYear
        ) {
            return coverages.values().stream()
                .filter(value -> recruitmentYear == null || value.recruitmentYear() == recruitmentYear).toList();
        }
        @Override public com.careeros.domain.acquisition.SourceYearCoverage saveSourceYearCoverage(
            com.careeros.domain.acquisition.SourceYearCoverage coverage
        ) { coverages.put(coverage.recruitmentYear(), coverage); return coverage; }
        @Override public com.careeros.domain.acquisition.SourceOnboardingCheckpoint saveCheckpoint(
            com.careeros.domain.acquisition.SourceOnboardingCheckpoint checkpoint
        ) { checkpoints.put(checkpoint.checkpoint(), checkpoint); return checkpoint; }
        @Override public List<com.careeros.domain.acquisition.SourceOnboardingCheckpoint> findCheckpoints(
            UUID sourceId
        ) { return List.copyOf(checkpoints.values()); }
        @Override public List<com.careeros.domain.acquisition.ArtifactImportFailure> saveImportFailures(
            List<com.careeros.domain.acquisition.ArtifactImportFailure> failures
        ) { importFailures.addAll(failures); return List.copyOf(failures); }
        @Override public List<com.careeros.domain.acquisition.ArtifactImportFailure> findImportFailures(
            UUID sourceId, UUID runId
        ) { return importFailures.stream().filter(value -> value.sourceId().equals(sourceId)
            && value.runId().equals(runId)).toList(); }
        @Override public long countImportFailures(UUID sourceId) {
            return importFailures.stream().filter(value -> value.sourceId().equals(sourceId)).count();
        }
        @Override public com.careeros.domain.acquisition.TargetSource.ConnectionStatus findTargetSourceStatus(
            String sourceCode
        ) { return com.careeros.domain.acquisition.TargetSource.ConnectionStatus.PARTIAL; }
        @Override public void updateTargetSourceStatus(
            String sourceCode, com.careeros.domain.acquisition.TargetSource.ConnectionStatus status,
            UUID recruitmentSourceId, Instant updatedAt
        ) {}
        @Override public long countActiveTargetJobs(UUID sourceId, int recruitmentYear) {
            return targetJobs.getOrDefault(recruitmentYear, 0L);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
