package com.careeros;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.careeros.application.AcquisitionPorts.*;
import com.careeros.application.AcquisitionService;
import com.careeros.application.SourceCompletionAuditService;
import com.careeros.domain.acquisition.AcquisitionChange;
import com.careeros.domain.acquisition.ArtifactDiscovery;
import com.careeros.domain.acquisition.AcquisitionChange.ChangeType;
import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.acquisition.RecruitmentSource.CrawlMode;
import com.careeros.domain.acquisition.RecruitmentSource.SourceType;
import com.careeros.domain.acquisition.SourceCrawlRun;
import com.careeros.domain.acquisition.SourceCrawlRun.RunStatus;
import com.careeros.domain.acquisition.SourceCrawlRun.RunTrigger;
import com.careeros.domain.acquisition.SourceYearCoverage;
import com.careeros.domain.acquisition.SourceYearCoverage.CoverageStatus;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AcquisitionApiTest {
    private static final UUID SOURCE_ID = UUID.fromString("01992f09-0000-7000-8000-000000000301");
    private static final UUID RUN_ID = UUID.fromString("01992f09-0000-7000-8000-000000000501");
    private static final UUID SNAPSHOT_ID = UUID.fromString("01992f09-0000-7000-8000-000000000701");
    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");
    private AcquisitionStore store;
    private AcquisitionService service;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        store = mock(AcquisitionStore.class);
        service = mock(AcquisitionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AcquisitionController(store, service))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test void listsSourcesWithoutExposingSelectorConfiguration() throws Exception {
        when(store.findSources()).thenReturn(List.of(source()));
        when(store.findTargetSourceStatus("ZJ_HRSS_INSTITUTION"))
            .thenReturn(com.careeros.domain.acquisition.TargetSource.ConnectionStatus.PARTIAL);
        when(store.findSourceYearCoverage(SOURCE_ID, null)).thenReturn(List.of(
            new SourceYearCoverage(SOURCE_ID, 2024, CoverageStatus.PARTIAL,
                3, 2, 2, 1, null, null, NOW, 2, 1, 1,
                java.time.LocalDate.of(2024, 3, 1), java.time.LocalDate.of(2024, 8, 1), "DOCUMENT_FAILURE")));
        when(store.findCheckpoints(SOURCE_ID)).thenReturn(List.of(new com.careeros.domain.acquisition.SourceOnboardingCheckpoint(
            SOURCE_ID, com.careeros.domain.acquisition.SourceOnboardingCheckpoint.Checkpoint.REGISTERED,
            com.careeros.domain.acquisition.SourceOnboardingCheckpoint.CheckpointStatus.VERIFIED,
            "官方来源已登记", NOW)));
        when(store.countImportFailures(SOURCE_ID)).thenReturn(2L);
        when(store.countDocumentImportFailures(SOURCE_ID)).thenReturn(2L);
        when(store.lifecycleCounts(SOURCE_ID)).thenReturn(new LifecycleCounts(7, 4, 2, 1));

        mvc.perform(get("/api/acquisition/sources"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].code").value("ZJ_HRSS_INSTITUTION"))
            .andExpect(jsonPath("$[0].configuration").doesNotExist())
            .andExpect(jsonPath("$[0].entryUri").value("https://rlsbt.zj.gov.cn/list"))
            .andExpect(jsonPath("$[0].connectionStatus").value("PARTIAL"))
            .andExpect(jsonPath("$[0].scopeLevel").value("CITY"))
            .andExpect(jsonPath("$[0].scopeCode").value("HANGZHOU"))
            .andExpect(jsonPath("$[0].priorityTier").value("P0"))
            .andExpect(jsonPath("$[0].coverageRole").value("PRIMARY"))
            .andExpect(jsonPath("$[0].accessStatus").value("ACCESSIBLE"))
            .andExpect(jsonPath("$[0].documentIssueCount").value(2))
            .andExpect(jsonPath("$[0].lifecycleDocumentCount").value(7))
            .andExpect(jsonPath("$[0].matchedLifecycleCount").value(4))
            .andExpect(jsonPath("$[0].unmatchedLifecycleCount").value(2))
            .andExpect(jsonPath("$[0].ambiguousLifecycleCount").value(1))
            .andExpect(jsonPath("$[0].coverage[0].listingPageCount").value(2))
            .andExpect(jsonPath("$[0].checkpoints[0].checkpoint").value("REGISTERED"))
            .andExpect(jsonPath("$[0].historicalFailureCount").value(2));
    }

    @Test void manualTriggerReturnsAcceptedRunAndLocation() throws Exception {
        when(service.run(SOURCE_ID, RunTrigger.MANUAL)).thenReturn(run());

        mvc.perform(post("/api/acquisition/sources/{id}/runs", SOURCE_ID))
            .andExpect(status().isAccepted())
            .andExpect(header().string("Location", "/api/acquisition/runs/" + RUN_ID))
            .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test void listsRegisteredDistrictTargetsEvenBeforeACollectorIsConnected() throws Exception {
        when(store.findTargetSources()).thenReturn(List.of(new TargetSourceRegistration(
            "HZ_GONGSHU_GOV", "拱墅区政府招聘", "杭州拱墅", "https://www.gongshu.gov.cn/",
            com.careeros.domain.acquisition.TargetSource.ConnectionStatus.NOT_CONNECTED,
            null, true, "DISTRICT", "HANGZHOU_GONGSHU", "P0", "PRIMARY")));

        mvc.perform(get("/api/acquisition/sources"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].code").value("HZ_GONGSHU_GOV"))
            .andExpect(jsonPath("$[0].id").value(nullValue()))
            .andExpect(jsonPath("$[0].enabled").value(false))
            .andExpect(jsonPath("$[0].accessStatus").value("NOT_CONFIGURED"))
            .andExpect(jsonPath("$[0].lifecycleDocumentCount").value(0))
            .andExpect(jsonPath("$[0].scopeLevel").value("DISTRICT"))
            .andExpect(jsonPath("$[0].priorityTier").value("P0"));
    }

    @Test void historicalTriggerReturnsRunAndCoverageForRequestedRange() throws Exception {
        when(service.backfill(SOURCE_ID, Set.of(2024, 2025, 2026))).thenReturn(run());
        when(store.findSourceYearCoverage(SOURCE_ID, null)).thenReturn(List.of(
            new SourceYearCoverage(SOURCE_ID, 2024, CoverageStatus.COMPLETE,
                12, 12, 12, 3, "official listing total=12", NOW, NOW),
            new SourceYearCoverage(SOURCE_ID, 2025, CoverageStatus.NO_TARGET_RECORDS,
                8, 8, 8, 0, "official listing total=8", NOW, NOW),
            new SourceYearCoverage(SOURCE_ID, 2026, CoverageStatus.PARTIAL,
                10, 9, 8, 2, null, null, NOW)));

        mvc.perform(post("/api/acquisition/sources/{id}/historical-runs", SOURCE_ID)
                .param("fromYear", "2024").param("toYear", "2026"))
            .andExpect(status().isAccepted())
            .andExpect(header().string("Location", "/api/acquisition/runs/" + RUN_ID))
            .andExpect(jsonPath("$.run.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.coverage", hasSize(3)))
            .andExpect(jsonPath("$.coverage[0].year").value(2024))
            .andExpect(jsonPath("$.coverage[2].status").value("PARTIAL"));
    }

    @Test void historicalTriggerRejectsReversedOrOutOfRangeYears() throws Exception {
        mvc.perform(post("/api/acquisition/sources/{id}/historical-runs", SOURCE_ID)
                .param("fromYear", "2026").param("toYear", "2024"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/acquisition/sources/{id}/historical-runs", SOURCE_ID)
                .param("fromYear", "1999").param("toYear", "2024"))
            .andExpect(status().isBadRequest());
    }

    @Test void coverageMakesUnknownCollectionStateExplicit() throws Exception {
        when(store.findSourceYearCoverage(SOURCE_ID, 2025)).thenReturn(List.of(
            new SourceYearCoverage(SOURCE_ID, 2025, CoverageStatus.ACCESS_FAILED,
                12, 0, 0, 0, null, null, NOW)));

        mvc.perform(get("/api/acquisition/coverage")
                .param("sourceId", SOURCE_ID.toString()).param("year", "2025"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].year").value(2025))
            .andExpect(jsonPath("$[0].status").value("ACCESS_FAILED"))
            .andExpect(jsonPath("$[0].supportsAbsenceConclusion").value(false))
            .andExpect(jsonPath("$[0].completionBasis").value(nullValue()));
    }

    @Test void changeFeedReturnsStableNextCursor() throws Exception {
        UUID changeId = UUID.fromString("01992f09-0000-7000-8000-000000000601");
        var change = new AcquisitionChange(changeId, RUN_ID, SOURCE_ID, UUID.randomUUID(),
            ChangeType.ADDED, null, "a".repeat(64), URI.create("https://official/notice"),
            Map.of("inserted", 1), NOW);
        ChangeCursor next = new ChangeCursor(NOW, changeId);
        when(store.findChanges(isNull(), eq(SOURCE_ID), eq(Set.of(ChangeType.ADDED)), eq(1)))
            .thenReturn(new ChangePage(List.of(change), next));

        mvc.perform(get("/api/acquisition/changes")
                .param("sourceId", SOURCE_ID.toString()).param("types", "ADDED").param("size", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].changeType").value("ADDED"))
            .andExpect(jsonPath("$.nextCursor", not(blankOrNullString())));
    }

    @Test void exposesUnresolvedDiscoveryInventoryForTheMissingArtifactDashboard() throws Exception {
        var discovery = new ArtifactDiscovery(UUID.randomUUID(), SOURCE_ID, RUN_ID, null,
            URI.create("https://official.example/2026/jobs.xlsx"),
            URI.create("https://official.example/2026/jobs.xlsx"), "2026 岗位表",
            com.careeros.domain.acquisition.AcquiredDocument.DocumentKind.ATTACHMENT,
            java.time.LocalDate.of(2026, 4, 1), ArtifactDiscovery.DiscoveryStatus.FETCH_FAILED,
            "HTTP_429", NOW, NOW, 1, "application/vnd.ms-excel", "a".repeat(64), 2048);
        when(store.findArtifactDiscoveries(SOURCE_ID, RUN_ID)).thenReturn(List.of(discovery));
        when(store.countUnresolvedArtifactDiscoveries(SOURCE_ID)).thenReturn(1L);

        mvc.perform(get("/api/acquisition/sources/{id}/discoveries", SOURCE_ID)
                .param("runId", RUN_ID.toString()).param("status", "FETCH_FAILED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].canonicalUri").value(discovery.canonicalUri().toString()))
            .andExpect(jsonPath("$[0].status").value("FETCH_FAILED"))
            .andExpect(jsonPath("$[0].mediaType").value("application/vnd.ms-excel"))
            .andExpect(jsonPath("$[0].rawChecksum").value("a".repeat(64)))
            .andExpect(jsonPath("$[0].sizeBytes").value(2048))
            .andExpect(jsonPath("$[0].classification").value("UNKNOWN"))
            .andExpect(jsonPath("$[0].unresolved").value(true));
        mvc.perform(get("/api/acquisition/sources/{id}/discovery-health", SOURCE_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unresolvedCount").value(1));
    }

    @Test void attachesPinnedAuditCompletionToSourcesAndCoverage() throws Exception {
        when(store.findSources()).thenReturn(List.of(source()));
        when(store.findTargetSources()).thenReturn(List.of());
        when(store.findTargetSourceStatus("ZJ_HRSS_INSTITUTION"))
            .thenReturn(com.careeros.domain.acquisition.TargetSource.ConnectionStatus.PARTIAL);
        when(store.findSourceYearCoverage(SOURCE_ID, null)).thenReturn(List.of(
            new SourceYearCoverage(SOURCE_ID, 2025, CoverageStatus.PARTIAL,
                1, 1, 1, 1, null, null, NOW)));
        when(store.findSourceYearCoverage(SOURCE_ID, 2025)).thenReturn(List.of(
            new SourceYearCoverage(SOURCE_ID, 2025, CoverageStatus.PARTIAL,
                1, 1, 1, 1, null, null, NOW)));
        when(store.findCheckpoints(SOURCE_ID)).thenReturn(List.of());
        when(store.countImportFailures(SOURCE_ID)).thenReturn(0L);
        when(store.countDocumentImportFailures(SOURCE_ID)).thenReturn(0L);
        when(store.lifecycleCounts(SOURCE_ID)).thenReturn(LifecycleCounts.none());
        SourceCompletionAuditService audit = mock(SourceCompletionAuditService.class);
        when(audit.get(SNAPSHOT_ID)).thenReturn("""
            {"sources":[{"code":"ZJ_HRSS_INSTITUTION","completion":
            {"auditSnapshotId":"01992f09-0000-7000-8000-000000000701","completionLevel":0,
            "years":[{"year":2025,"conclusion":"UNKNOWN","supportsAbsenceConclusion":false}]}}]}
            """);
        var pinnedMvc = MockMvcBuilders.standaloneSetup(new AcquisitionController(
            store, service, audit, new ObjectMapper().findAndRegisterModules()))
            .setControllerAdvice(new ApiExceptionHandler()).build();

        pinnedMvc.perform(get("/api/acquisition/sources").param("auditSnapshotId", SNAPSHOT_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].completion.auditSnapshotId").value(SNAPSHOT_ID.toString()));
        pinnedMvc.perform(get("/api/acquisition/coverage").param("sourceId", SOURCE_ID.toString())
                .param("year", "2025").param("auditSnapshotId", SNAPSHOT_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].assessment.year").value(2025))
            .andExpect(jsonPath("$[0].assessment.conclusion").value("UNKNOWN"));
    }

    @Test void invalidCursorAndOversizedPageReturnProblemDetails() throws Exception {
        mvc.perform(get("/api/acquisition/changes").param("cursor", "not-base64"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/acquisition/changes").param("size", "201"))
            .andExpect(status().isBadRequest());
    }

    private static RecruitmentSource source() {
        return new RecruitmentSource(SOURCE_ID, "ZJ_HRSS_INSTITUTION", "浙江人社",
            URI.create("https://rlsbt.zj.gov.cn/"), URI.create("https://rlsbt.zj.gov.cn/list"),
            SourceType.OFFICIAL_GOVERNMENT, "浙江", CrawlMode.STATIC_HTML, true,
            "0 10 8 * * *", "Asia/Shanghai", Duration.ofSeconds(1), Map.of("secret", "selector"),
            NOW, null, NOW, 0, NOW, NOW);
    }

    private static SourceCrawlRun run() {
        return new SourceCrawlRun(RUN_ID, SOURCE_ID, RunTrigger.MANUAL, RunStatus.SUCCEEDED,
            NOW, NOW.plusSeconds(2), 1, 1, 0, 1, 0, 0, 0, null, null);
    }
}
