package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.careeros.application.AdmissionScopeReviewService;
import com.careeros.application.AdmissionScopeReviewService.Command;
import com.careeros.application.AdmissionScopeReviewService.Decision;
import com.careeros.application.AdmissionScopeReviewService.Outcome;
import com.careeros.application.AdmissionScopeReviewService.RecordedDecision;
import com.careeros.domain.DomainEnums.DataQualityStatus;
import com.careeros.domain.DomainEnums.JobAdmissionReason;
import com.careeros.domain.DomainEnums.TargetScopeStatus;
import com.careeros.domain.JobAdmission;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/** 纳入可信机会池只给复核人，且复核人身份取自认证而不是请求体。 */
@WebMvcTest(controllers = JobAdmissionController.class)
@Import(SecurityConfiguration.class)
class JobAdmissionApiTest {
    private static final UUID JOB = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-24T08:00:00Z");
    private static final String INCLUDE = """
        {"decision":"INCLUDE","contentFingerprint":"%s","note":"信息中心岗","reviewer":"someone-else"}
        """.formatted("f".repeat(64));

    @Autowired MockMvc mvc;
    @MockBean AdmissionScopeReviewService service;
    @MockBean com.careeros.application.OfficialJobAdmissionService admissions;

    @Test void anonymousCallersCannotAdmitJobs() throws Exception {
        mvc.perform(post("/api/v1/job-admissions/{id}/scope-decisions", JOB)
                .contentType(MediaType.APPLICATION_JSON).content(INCLUDE))
            .andExpect(status().isUnauthorized());
        verify(service, never()).decide(any());
    }

    @Test
    @WithMockUser(username = "viewer", roles = "VIEWER")
    void anAuthenticatedCallerWithoutTheReviewerRoleCannotAdmitJobs() throws Exception {
        mvc.perform(post("/api/v1/job-admissions/{id}/scope-decisions", JOB)
                .contentType(MediaType.APPLICATION_JSON).content(INCLUDE))
            .andExpect(status().isForbidden());
        verify(service, never()).decide(any());
    }

    @Test
    @WithMockUser(username = "reviewer-a", roles = "REVIEWER")
    void theReviewerIsTheAuthenticatedPrincipalAndTheResponseSaysWhetherItIsNowTrusted() throws Exception {
        var admission = new JobAdmission(JOB, DataQualityStatus.VERIFIED, TargetScopeStatus.INCLUDED,
            Set.of(JobAdmissionReason.TARGET_TECHNICAL_ROLE, JobAdmissionReason.HUMAN_SCOPE_INCLUDED),
            AdmissionScopeReviewService.EVALUATOR_VERSION, NOW, true);
        when(service.decide(any())).thenReturn(new Outcome(admission, new RecordedDecision(UUID.randomUUID(), JOB,
            Decision.INCLUDE, "f".repeat(64), "reviewer-a", "信息中心岗", DataQualityStatus.VERIFIED,
            TargetScopeStatus.INCLUDED, NOW)));

        mvc.perform(post("/api/v1/job-admissions/{id}/scope-decisions", JOB)
                .contentType(MediaType.APPLICATION_JSON).content(INCLUDE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.admitted").value(true))
            .andExpect(jsonPath("$.targetScopeStatus").value("INCLUDED"))
            .andExpect(jsonPath("$.reviewer").value("reviewer-a"));

        var command = ArgumentCaptor.forClass(Command.class);
        verify(service).decide(command.capture());
        assertThat(command.getValue().reviewer()).isEqualTo("reviewer-a");
        assertThat(command.getValue().decision()).isEqualTo(Decision.INCLUDE);
    }

    @Test
    @WithMockUser(username = "reviewer-a", roles = "REVIEWER")
    void blockersAreReturnedOneByOne() throws Exception {
        when(service.decide(any())).thenThrow(new AdmissionScopeReviewService.NotAdmissibleException(
            List.of("用工身份未知，不能进入可信机会池", "数据质量为 NORMALIZED")));

        mvc.perform(post("/api/v1/job-admissions/{id}/scope-decisions", JOB)
                .contentType(MediaType.APPLICATION_JSON).content(INCLUDE))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("JOB_NOT_ADMISSIBLE"))
            .andExpect(jsonPath("$.blockers.length()").value(2));
    }

    @Test
    @WithMockUser(username = "viewer", roles = "VIEWER")
    void onlyReviewersCanReclassifyTheLibrary() throws Exception {
        mvc.perform(post("/api/v1/job-admissions/reclassifications"))
            .andExpect(status().isForbidden());
        verify(admissions, never()).reclassifyActive(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @WithMockUser(username = "reviewer-a", roles = "REVIEWER")
    void aDecisionOnChangedContentIsAConflict() throws Exception {
        when(service.decide(any())).thenThrow(new AdmissionScopeReviewService.StaleContentException("内容已变化"));

        mvc.perform(post("/api/v1/job-admissions/{id}/scope-decisions", JOB)
                .contentType(MediaType.APPLICATION_JSON).content(INCLUDE))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("JOB_CONTENT_CHANGED"));
    }
}
