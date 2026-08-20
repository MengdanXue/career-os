package com.careeros;

import static com.careeros.application.DecisionPorts.*;
import static com.careeros.domain.DomainEnums.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.careeros.application.*;
import com.careeros.domain.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DecisionApiTest {
    private static final UUID CANDIDATE_ID = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private DecisionIntelligenceService decisions;
    private DecisionRankingService rankings;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        decisions = mock(DecisionIntelligenceService.class);
        rankings = mock(DecisionRankingService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DecisionController(decisions, rankings, new DecisionExplanationService()))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test void createsAuditableDecisionResource() throws Exception {
        when(decisions.assess(eq(CANDIDATE_ID), eq(JOB_ID), any())).thenReturn(bundle());

        mvc.perform(post("/api/v1/candidates/{candidateId}/job-decisions/{jobId}", CANDIDATE_ID, JOB_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId").value(JOB_ID.toString()))
            .andExpect(jsonPath("$.eligibilityStatus").value("ELIGIBLE"))
            .andExpect(jsonPath("$.tier").value("T1"))
            .andExpect(jsonPath("$.fit.score").value(70))
            .andExpect(jsonPath("$.stability.coveragePercent").value(40))
            .andExpect(jsonPath("$.disclaimer").value("机会决策指数，不是录取概率"));
    }

    @Test void listsPaginatedRankings() throws Exception {
        when(rankings.rank(eq(CANDIDATE_ID), any(), any())).thenReturn(new DecisionRankingService.RankingPage(List.of(bundle()), 0, 20, 1));

        mvc.perform(get("/api/v1/candidates/{candidateId}/job-decisions", CANDIDATE_ID).param("tier", "T1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].tier").value("T1"))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test void missingCandidateReturnsStableProblemCode() throws Exception {
        when(decisions.current(CANDIDATE_ID, JOB_ID))
            .thenThrow(new DecisionExceptions.CandidateNotFoundException("Candidate not found"));

        mvc.perform(get("/api/v1/candidates/{candidateId}/job-decisions/{jobId}", CANDIDATE_ID, JOB_ID))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("CANDIDATE_NOT_FOUND"));
    }

    @Test void getReturnsNotFoundWithoutCreatingAMissingDecision() throws Exception {
        when(decisions.current(CANDIDATE_ID, JOB_ID))
            .thenThrow(new DecisionExceptions.DecisionNotFoundException("Decision not found"));

        mvc.perform(get("/api/v1/candidates/{candidateId}/job-decisions/{jobId}", CANDIDATE_ID, JOB_ID))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("DECISION_NOT_FOUND"));

        verify(decisions, never()).assess(any(), any(), any());
    }

    private static DecisionBundle bundle() {
        UUID eventId=UUID.randomUUID(), organizationId=UUID.randomUUID(), eligibilityId=UUID.randomUUID(), fitId=UUID.randomUUID(), stabilityId=UUID.randomUUID();
        UUID evidenceId=UUID.randomUUID();
        var job = new JobPosting(JOB_ID,eventId,organizationId,"A01","信息中心技术岗",JobFamily.INFORMATION_SYSTEMS,EmploymentType.ESTABLISHMENT,"杭州",1,EducationLevel.BACHELOR,Set.of(),Set.of(),null,null,null,Set.of(),"Java运维","https://example.gov.cn",List.of(evidenceId));
        var organization = new Organization(organizationId,"杭州市信息中心",OrganizationType.PUBLIC_INSTITUTION,null,"浙江","杭州",null,null,null);
        var event = new RecruitmentEvent(eventId,"公开招聘",2026,EventType.PUBLIC_INSTITUTION,LocalDate.of(2026,8,1),null,LocalDate.of(2026,9,1),"https://example.gov.cn",EmploymentType.ESTABLISHMENT,List.of(evidenceId));
        var eligibility = new EligibilityAssessment(eligibilityId,CANDIDATE_ID,JOB_ID,EligibilityStatus.ELIGIBLE,Map.of(),List.of(evidenceId),EligibilityEvaluator.VERSION,NOW,"v2","a".repeat(64));
        var fitDimension = new AssessmentDimension(AssessmentDimensionType.MAJOR_FIT,70,100,AssessmentFactStatus.EXPLICIT,"FIXTURE","fixture",List.of(evidenceId));
        var stabilityDimension = new AssessmentDimension(AssessmentDimensionType.EMPLOYMENT_SECURITY,40,40,AssessmentFactStatus.EXPLICIT,"FIXTURE","fixture",List.of(evidenceId));
        var unknownStability = new AssessmentDimension(AssessmentDimensionType.FUNDING_STABILITY,0,60,AssessmentFactStatus.UNKNOWN,"FIXTURE_UNKNOWN","fixture unknown",List.of());
        var fit = new FitAssessment(fitId,CANDIDATE_ID,JOB_ID,List.of(fitDimension),FitEvaluator.VERSION,"v2","a".repeat(64),NOW);
        var stability = new StabilityAssessment(stabilityId,CANDIDATE_ID,JOB_ID,List.of(stabilityDimension,unknownStability),StabilityEvaluator.VERSION,"v2","a".repeat(64),NOW);
        var decision = new DecisionAssessment(UUID.randomUUID(),CANDIDATE_ID,JOB_ID,eligibilityId,fitId,stabilityId,EligibilityStatus.ELIGIBLE,OpportunityTier.T1,RecommendationStatus.RECOMMENDED,70,40,55,DecisionIntelligenceService.VERSION,"v2","a".repeat(64),NOW);
        return new DecisionBundle(eligibility,fit,stability,decision,new JobContext(job,organization,event,"a".repeat(64),true));
    }
}
