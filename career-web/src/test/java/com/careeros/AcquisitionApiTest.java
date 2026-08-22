package com.careeros;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.careeros.application.AcquisitionPorts.*;
import com.careeros.application.AcquisitionService;
import com.careeros.domain.acquisition.AcquisitionChange;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AcquisitionApiTest {
    private static final UUID SOURCE_ID = UUID.fromString("01992f09-0000-7000-8000-000000000301");
    private static final UUID RUN_ID = UUID.fromString("01992f09-0000-7000-8000-000000000501");
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

        mvc.perform(get("/api/acquisition/sources"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].code").value("ZJ_HRSS_INSTITUTION"))
            .andExpect(jsonPath("$[0].configuration").doesNotExist())
            .andExpect(jsonPath("$[0].entryUri").value("https://rlsbt.zj.gov.cn/list"));
    }

    @Test void manualTriggerReturnsAcceptedRunAndLocation() throws Exception {
        when(service.run(SOURCE_ID, RunTrigger.MANUAL)).thenReturn(run());

        mvc.perform(post("/api/acquisition/sources/{id}/runs", SOURCE_ID))
            .andExpect(status().isAccepted())
            .andExpect(header().string("Location", "/api/acquisition/runs/" + RUN_ID))
            .andExpect(jsonPath("$.status").value("SUCCEEDED"));
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
            null, null, NOW, 0, NOW, NOW);
    }

    private static SourceCrawlRun run() {
        return new SourceCrawlRun(RUN_ID, SOURCE_ID, RunTrigger.MANUAL, RunStatus.SUCCEEDED,
            NOW, NOW.plusSeconds(2), 1, 1, 0, 1, 0, 0, 0, null, null);
    }
}
