package com.careeros;

import static com.careeros.domain.DomainEnums.EligibilityStatus.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.careeros.application.personal.CandidateDecisionDiffService;
import com.careeros.application.personal.DecisionChangeSummary;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DecisionChangeApiTest {
    private static final UUID CANDIDATE_ID = UUID.randomUUID();
    private CandidateDecisionDiffService service;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        service = mock(CandidateDecisionDiffService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DecisionChangeController(service))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test void recomputesAndReturnsAnAuditableSummary() throws Exception {
        UUID jobId = UUID.randomUUID();
        var summary = new DecisionChangeSummary(CANDIDATE_ID, "old-v1", "current-v2",
            LocalDate.of(2026, 8, 24), true, null, 1, 1, 0,
            List.of(new DecisionChangeSummary.AffectedJob(jobId, "信息岗位", "杭州市信息中心",
                UNCERTAIN, ELIGIBLE, List.of("工作经历：待确认 → 可报"), "/opportunities/" + jobId)));
        when(service.recompute(CANDIDATE_ID, "old-v1", LocalDate.of(2026, 8, 24))).thenReturn(summary);

        mvc.perform(post("/api/v1/candidates/{candidateId}/decision-change-summaries/{profileVersion}",
                CANDIDATE_ID, "old-v1").param("asOf", "2026-08-24"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(true))
            .andExpect(jsonPath("$.previousProfileVersion").value("old-v1"))
            .andExpect(jsonPath("$.currentProfileVersion").value("current-v2"))
            .andExpect(jsonPath("$.newlyEligibleCount").value(1))
            .andExpect(jsonPath("$.resolvedUncertaintyCount").value(1))
            .andExpect(jsonPath("$.affectedJobs[0].jobId").value(jobId.toString()))
            .andExpect(jsonPath("$.affectedJobs[0].reasons[0]").value("工作经历：待确认 → 可报"));
    }

    @Test void preservesUnavailableHistoryAsUnknownCounts() throws Exception {
        when(service.recompute(CANDIDATE_ID, "missing-v1", LocalDate.of(2026, 8, 24)))
            .thenReturn(new DecisionChangeSummary(CANDIDATE_ID, "missing-v1", "current-v2",
                LocalDate.of(2026, 8, 24), false, "没有历史结论", null, null, null, List.of()));

        mvc.perform(post("/api/v1/candidates/{candidateId}/decision-change-summaries/{profileVersion}",
                CANDIDATE_ID, "missing-v1").param("asOf", "2026-08-24"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.message").value("没有历史结论"))
            .andExpect(jsonPath("$.newlyEligibleCount").doesNotExist())
            .andExpect(jsonPath("$.affectedJobs").isEmpty());
    }
}
