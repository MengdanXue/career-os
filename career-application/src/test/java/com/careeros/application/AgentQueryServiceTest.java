package com.careeros.application;

import static com.careeros.application.DecisionPorts.*;
import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.domain.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentQueryServiceTest {
    private static final UUID CANDIDATE_ID=UUID.randomUUID();
    private static final Instant NOW=Instant.parse("2026-08-20T12:00:00Z");

    @Test void naturalLanguageBecomesBoundedToolFiltersAndWorksWithoutModel() {
        var captured=new AtomicReference<DecisionRankingService.RankingQuery>();
        AgentQueryService.RankingPort port=(candidateId,query,now)->{captured.set(query);return new DecisionRankingService.RankingPage(List.of(bundle()),0,5,1);};

        var result=new AgentQueryService(port,new DecisionExplanationService(),Optional.empty())
            .query(CANDIDATE_ID,"帮我找杭州 T1 信息化岗位",5,NOW);

        assertThat(captured.get().location()).isEqualTo("杭州");
        assertThat(captured.get().tier()).isEqualTo(OpportunityTier.T1);
        assertThat(captured.get().jobFamily()).isEqualTo(JobFamily.INFORMATION_SYSTEMS);
        assertThat(result.answer()).contains("信息中心技术岗").contains("T1").contains("70");
        assertThat(result.modelPhrased()).isFalse();
        assertThat(result.fallbackUsed()).isFalse();
    }

    @Test void modelFailureFallsBackAndContradictoryPhrasingCannotChangeStructuredDecision() {
        AgentQueryService.RankingPort port=(candidateId,query,now)->new DecisionRankingService.RankingPage(List.of(bundle()),0,5,1);
        var failing=new AgentQueryService(port,new DecisionExplanationService(),Optional.of(context->{throw new IllegalStateException("model down");}));
        var fallback=failing.query(CANDIDATE_ID,"杭州稳定岗位",5,NOW);

        assertThat(fallback.fallbackUsed()).isTrue();
        assertThat(fallback.decisions().getFirst().decision().tier()).isEqualTo(OpportunityTier.T1);

        var contradictory=new AgentQueryService(port,new DecisionExplanationService(),Optional.of(context->"这是T3岗位，匹配0分"))
            .query(CANDIDATE_ID,"杭州稳定岗位",5,NOW);
        assertThat(contradictory.answer()).contains("T3岗位");
        assertThat(contradictory.decisions().getFirst().decision().tier()).isEqualTo(OpportunityTier.T1);
        assertThat(contradictory.decisions().getFirst().decision().fitScore()).isEqualTo(70);
    }

    private static DecisionBundle bundle() {
        UUID jobId=UUID.randomUUID(),eventId=UUID.randomUUID(),organizationId=UUID.randomUUID(),evidenceId=UUID.randomUUID();
        UUID eligibilityId=UUID.randomUUID(),fitId=UUID.randomUUID(),stabilityId=UUID.randomUUID();
        var job=new JobPosting(jobId,eventId,organizationId,"A","信息中心技术岗",JobFamily.INFORMATION_SYSTEMS,EmploymentType.ESTABLISHMENT,"杭州",1,EducationLevel.BACHELOR,Set.of(),Set.of(),null,null,null,Set.of(),"Java","https://example.gov.cn",List.of(evidenceId));
        var org=new Organization(organizationId,"杭州市信息中心",OrganizationType.PUBLIC_INSTITUTION,null,"浙江","杭州",null,null,null);
        var event=new RecruitmentEvent(eventId,"招聘",2026,EventType.PUBLIC_INSTITUTION,LocalDate.of(2026,8,1),null,LocalDate.of(2026,9,1),"https://example.gov.cn",EmploymentType.ESTABLISHMENT,List.of(evidenceId));
        var eligibility=new EligibilityAssessment(eligibilityId,CANDIDATE_ID,jobId,EligibilityStatus.ELIGIBLE,Map.of(),List.of(evidenceId),EligibilityEvaluator.VERSION,NOW,"v2","a".repeat(64));
        var fit=new FitAssessment(fitId,CANDIDATE_ID,jobId,List.of(new AssessmentDimension(AssessmentDimensionType.MAJOR_FIT,70,100,AssessmentFactStatus.EXPLICIT,"FIXTURE","fixture",List.of(evidenceId))),FitEvaluator.VERSION,"v2","a".repeat(64),NOW);
        var stability=new StabilityAssessment(stabilityId,CANDIDATE_ID,jobId,List.of(new AssessmentDimension(AssessmentDimensionType.EMPLOYMENT_SECURITY,40,100,AssessmentFactStatus.EXPLICIT,"FIXTURE","fixture",List.of(evidenceId))),StabilityEvaluator.VERSION,"v2","a".repeat(64),NOW);
        var decision=new DecisionAssessment(UUID.randomUUID(),CANDIDATE_ID,jobId,eligibilityId,fitId,stabilityId,EligibilityStatus.ELIGIBLE,OpportunityTier.T1,RecommendationStatus.RECOMMENDED,70,40,100,DecisionIntelligenceService.VERSION,"v2","a".repeat(64),NOW);
        return new DecisionBundle(eligibility,fit,stability,decision,new JobContext(job,org,event,"a".repeat(64),true));
    }
}
