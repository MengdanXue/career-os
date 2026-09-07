package com.careeros.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.careeros.application.AcquisitionHttpPorts.DiscoveredLink;
import com.careeros.application.AcquisitionHttpPorts.FetchedDocument;
import com.careeros.application.AcquisitionPorts.AcquisitionStore;
import com.careeros.application.AcquisitionService;
import com.careeros.application.ExtractionPorts.ArtifactStore;
import com.careeros.application.ExtractionPorts.ExtractionResult;
import com.careeros.application.ExtractionService;
import com.careeros.application.JobUpsertService;
import com.careeros.application.JobUpsertService.JobUpsertBatch;
import com.careeros.application.JobUpsertService.JobUpsertResult;
import com.careeros.application.OfficialJobAdmissionService;
import com.careeros.domain.ExtractionRun;
import com.careeros.domain.SourceArtifact;
import com.careeros.domain.acquisition.AcquiredDocument;
import com.careeros.domain.acquisition.AcquiredDocument.DocumentKind;
import com.careeros.domain.acquisition.AcquiredDocument.DocumentState;
import com.careeros.domain.acquisition.ArtifactImportFailure;
import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.acquisition.RecruitmentSource.CrawlMode;
import com.careeros.domain.acquisition.RecruitmentSource.SourceType;
import com.careeros.domain.acquisition.SourceCrawlRun.RunStatus;
import com.careeros.domain.acquisition.SourceCrawlRun.RunTrigger;
import com.careeros.infrastructure.acquisition.Phase2DocumentProcessor;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Phase2DocumentReprocessingTest {
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");
    private static final URI PAGE = URI.create(
        "https://zp.hz-hospital.com/index/index/announcement_desc/id/212.html");

    @ParameterizedTest(name = "v14 document is reparsed once after HTTP {0}")
    @ValueSource(ints = {200, 304})
    void unchangedV14DocumentIsImportedOnceByCurrentProcessor(int httpStatus) throws Exception {
        var fixture = new Fixture(html("35周岁以下", "", false), httpStatus);

        var reparsed = fixture.service.run(fixture.source.id(), RunTrigger.MANUAL);
        var stable = fixture.service.run(fixture.source.id(), RunTrigger.MANUAL);

        assertThat(reparsed.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(stable.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(reparsed.unchangedCount()).isEqualTo(1);
        assertThat(stable.unchangedCount()).isEqualTo(1);
        assertThat(fixture.batches).singleElement().satisfies(batch ->
            assertThat(batch.jobs()).singleElement().satisfies(job -> {
                assertThat(job.title()).isEqualTo("系统工程师");
                assertThat(job.maximumAge()).isEqualTo(35);
                assertThat(job.minimumExperienceYears()).isNull();
            }));
        assertThat(fixture.document.get().lastProcessorVersion())
            .isEqualTo(fixture.processor.version()).isNotEqualTo("official-fact-fusion-v14");
        assertThat(fixture.document.get().lastHttpStatus()).isEqualTo(httpStatus);
        assertThat(fixture.document.get().contentFingerprint()).isEqualTo(fixture.fingerprint);
        assertThat(fixture.failures).isEmpty();
    }

    @ParameterizedTest(name = "qualification review survives preceding malformed row: {0}")
    @ValueSource(booleans = {false, true})
    void webpageQualificationWarningsPersistWithOriginalSourceRowAndText(boolean malformedRow) throws Exception {
        var fixture = new Fixture(html("硕士35周岁以下<br>博士不限",
            "本科需3年工作经验<br>硕士不限", malformedRow), 200);
        // Use a known stale version to test review propagation independently of the v14 bump.
        var prior = fixture.document.get();
        fixture.document.set(prior.processed(prior.contentFingerprint(), "official-fact-fusion-v13"));

        var result = fixture.service.run(fixture.source.id(), RunTrigger.MANUAL);

        assertThat(result.status()).isEqualTo(RunStatus.PARTIALLY_SUCCEEDED);
        assertThat(fixture.batches).singleElement().satisfies(batch -> {
            assertThat(batch.completeSnapshot()).isEqualTo(!malformedRow);
            assertThat(batch.jobs()).singleElement().satisfies(job -> {
                assertThat(job.maximumAge()).isNull();
                assertThat(job.minimumExperienceYears()).isNull();
                assertThat(job.ageRequirementText()).isEqualTo("硕士35周岁以下\n博士不限");
                assertThat(job.originalRequirementText()).contains("本科需3年工作经验\n硕士不限");
            });
        });
        assertThat(fixture.failures).hasSize(malformedRow ? 3 : 2);
        assertThat(fixture.failures.stream()
            .filter(issue -> issue.errorCode().equals("ELIGIBILITY_NEEDS_REVIEW")))
            .hasSize(2).allSatisfy(issue -> {
                assertThat(issue.sheetName()).isEqualTo("网页岗位表");
                assertThat(issue.rowNumber()).isEqualTo(5);
                assertThat(issue.documentId()).isEqualTo(fixture.document.get().id());
                assertThat(issue.stage()).isEqualTo(ArtifactImportFailure.FailureStage.ROW_PARSE_FAILED);
            }).anySatisfy(issue ->
                assertThat(issue.safeMessage()).contains("年龄", "硕士35周岁以下\n博士不限", "待核实"))
            .anySatisfy(issue ->
                assertThat(issue.safeMessage()).contains("工作经历", "本科需3年工作经验\n硕士不限", "待核实"));
        if (malformedRow) {
            assertThat(fixture.failures).anySatisfy(issue -> {
                assertThat(issue.rowNumber()).isEqualTo(3);
                assertThat(issue.errorCode()).isEqualTo("MISSING_JOB_TITLE");
            });
        }
    }

    private static byte[] html(String age, String experience, boolean malformedRow) {
        return ("""
            <html><title>杭州市第一人民医院2026年公开招聘</title><body><table>
            <tr><td colspan="9">岗位计划</td></tr>
            <tr><th>科室</th><th>岗位名称</th><th>岗位类别</th><th>学历</th><th>专业</th>
            <th>招聘对象</th><th>人数</th><th>年龄</th><th>工作经历</th></tr>
            %s
            <tr></tr>
            <tr><td>信息中心</td><td>系统工程师</td><td>专业技术</td><td>硕士</td><td>计算机</td>
            <td>社会人员</td><td>1</td><td>%s</td><td>%s</td></tr>
            </table></body></html>
            """).formatted(malformedRow ? "<tr><td>缺少岗位名称</td></tr>" : "<tr></tr>", age, experience)
            .getBytes(StandardCharsets.UTF_8);
    }

    private static final class Fixture {
        final RecruitmentSource source = new RecruitmentSource(UUID.randomUUID(), "HOSPITAL", "医院招聘",
            URI.create("https://zp.hz-hospital.com/"), URI.create("https://zp.hz-hospital.com/list"),
            SourceType.OFFICIAL_ORGANIZATION, "杭州", CrawlMode.STATIC_HTML, true,
            "0 0 8 * * *", "Asia/Shanghai", Duration.ZERO, Map.of(), null, null, NOW, 0, NOW, NOW);
        final List<JobUpsertBatch> batches = new ArrayList<>();
        final List<ArtifactImportFailure> failures = new ArrayList<>();
        final AtomicReference<AcquiredDocument> document;
        final String fingerprint;
        final Phase2DocumentProcessor processor;
        final AcquisitionService service;

        Fixture(byte[] html, int httpStatus) throws Exception {
            fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(html));
            var storageUri = URI.create("memory:/hospital.html");
            document = new AtomicReference<>(new AcquiredDocument(UUID.randomUUID(), source.id(), PAGE, null,
                DocumentKind.ANNOUNCEMENT, "text/html", fingerprint, "v14-etag", null, storageUri,
                DocumentState.ACTIVE, NOW, NOW, NOW, null, 0, 200, fingerprint, "official-fact-fusion-v14", 0));
            var store = mock(AcquisitionStore.class);
            when(store.findSource(source.id())).thenReturn(source);
            when(store.findDocument(source.id(), PAGE)).thenAnswer(call -> Optional.of(document.get()));
            when(store.saveDocument(any())).thenAnswer(call -> {
                document.set(call.getArgument(0));
                return document.get();
            });
            when(store.saveRun(any())).thenAnswer(call -> call.getArgument(0));
            when(store.saveImportFailures(any())).thenAnswer(call -> {
                List<ArtifactImportFailure> recorded = call.getArgument(0);
                failures.addAll(recorded);
                return recorded;
            });
            var artifacts = mock(ArtifactStore.class);
            when(artifacts.put(any(), any(), any())).thenReturn(new SourceArtifact(
                UUID.randomUUID(), fingerprint, "text/html", html.length, storageUri.toString(), NOW));
            when(artifacts.open(any())).thenAnswer(call -> new ByteArrayInputStream(html));
            var events = mock(RecruitmentEventJpaRepository.class);
            var event = new JpaModels.RecruitmentEventEntity();
            event.id = UUID.randomUUID();
            when(events.findFirstBySourceUrl(PAGE.toString())).thenReturn(Optional.of(event));
            when(events.save(any())).thenAnswer(call -> call.getArgument(0));
            var organizations = mock(OrganizationJpaRepository.class);
            var organization = new JpaModels.OrganizationEntity();
            organization.id = UUID.randomUUID(); organization.name = "杭州市第一人民医院";
            when(organizations.findFirstByName(organization.name)).thenReturn(Optional.of(organization));
            var upserts = mock(JobUpsertService.class);
            when(upserts.upsert(any())).thenAnswer(call -> {
                batches.add(call.getArgument(0));
                return new JobUpsertResult(0, 1, 0, 0, List.of(UUID.randomUUID()));
            });
            var extractions = mock(ExtractionService.class);
            var extraction = mock(ExtractionRun.class);
            when(extraction.id()).thenReturn(UUID.randomUUID());
            when(extraction.evidenceId()).thenReturn(UUID.randomUUID());
            when(extractions.submit(any())).thenReturn(new ExtractionResult(extraction, Optional.empty(), false));
            processor = new Phase2DocumentProcessor(extractions, mock(OfficialExcelImportService.class),
                new OfficialAnnouncementFactService(events), new HospitalOfficialJobImportService(events,
                    organizations, upserts, mock(OfficialJobAdmissionService.class), new OfficialJobFieldMapper()));
            service = new AcquisitionService(store, (code, wait, work) -> Optional.of(work.get()),
                (configured, page, body) -> List.of(new DiscoveredLink(PAGE, "杭州市第一人民医院2026年公开招聘")),
                request -> request.uri().equals(PAGE)
                    ? new FetchedDocument(PAGE, httpStatus, httpStatus == 304 ? null : "text/html",
                        httpStatus == 304 ? new byte[0] : html, "v14-etag", null)
                    : new FetchedDocument(request.uri(), 200, "text/html", "list".getBytes(StandardCharsets.UTF_8), null, null),
                (configured, page, body) -> List.of(), processor, artifacts,
                (configured, after) -> after.plus(Duration.ofDays(1)), Clock.fixed(NOW, ZoneOffset.UTC), 1_000_000);
        }
    }
}
