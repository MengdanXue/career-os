package com.careeros.application;

import com.careeros.domain.*;
import com.careeros.domain.DomainEnums.*;
import java.time.Instant;
import java.util.UUID;

public final class CareerDecisionService {
    private final RepositoryPorts.CandidateProfiles candidates;
    private final RepositoryPorts.JobPostings jobs;
    private final RepositoryPorts.Organizations organizations;
    private final RepositoryPorts.EligibilityAssessments assessments;
    private final RepositoryPorts.Opportunities opportunities;
    private final EligibilityEvaluator evaluator;
    private final OpportunityTierClassifier tierClassifier;

    public CareerDecisionService(RepositoryPorts.CandidateProfiles candidates, RepositoryPorts.JobPostings jobs, RepositoryPorts.Organizations organizations, RepositoryPorts.EligibilityAssessments assessments, RepositoryPorts.Opportunities opportunities, EligibilityEvaluator evaluator, OpportunityTierClassifier tierClassifier) {
        this.candidates=candidates; this.jobs=jobs; this.organizations=organizations; this.assessments=assessments; this.opportunities=opportunities; this.evaluator=evaluator; this.tierClassifier=tierClassifier;
    }

    public DecisionResult assess(UUID candidateId, UUID jobId, Instant now) {
        var candidate=candidates.findById(candidateId).orElseThrow(() -> new IllegalArgumentException("Candidate not found: "+candidateId));
        var job=jobs.findById(jobId).orElseThrow(() -> new IllegalArgumentException("Job not found: "+jobId));
        var evaluated=evaluator.evaluate(candidate,job,now);
        var existingAssessment=assessments.findAll().stream().filter(value->value.candidateProfileId().equals(candidateId)&&value.jobPostingId().equals(jobId)&&value.evaluatorVersion().equals(EligibilityEvaluator.VERSION)).findFirst();
        var assessment=assessments.save(existingAssessment.map(value->new EligibilityAssessment(value.id(),candidateId,jobId,evaluated.status(),evaluated.ruleResults(),evaluated.requiredConfirmations(),evaluated.evidenceIds(),evaluated.evaluatorVersion(),now)).orElse(evaluated));
        int score=score(candidate,job,assessment.status());
        var existingOpportunity=opportunities.findAll().stream().filter(value->value.candidateProfileId().equals(candidateId)&&value.jobPostingId().equals(jobId)).findFirst();
        var opportunity=opportunities.save(existingOpportunity.map(value->new Opportunity(value.id(),candidate.id(),job.id(),assessment.id(),value.status(),score,explainScore(assessment.status(),score),value.createdAt(),now)).orElseGet(()->new Opportunity(UUID.randomUUID(),candidate.id(),job.id(),assessment.id(),OpportunityStatus.NEW,score,explainScore(assessment.status(),score),now,now)));
        var organization=organizations.findById(job.organizationId()).orElseThrow(() -> new IllegalArgumentException("Organization not found: "+job.organizationId()));
        return new DecisionResult(assessment,opportunity,tierClassifier.classify(job,organization));
    }

    int score(CandidateProfile candidate, JobPosting job, EligibilityStatus status) {
        // TODO(§6.2): matchScore 违反"禁止压成一个不可解释的匹配分"，待拆成
        // fit/chance/stability/growth/future/preparation_cost 六维后删除。
        if (status==EligibilityStatus.INELIGIBLE||status==EligibilityStatus.CONFLICTING_EVIDENCE) return 0;
        int score=switch (status) {
            case NEEDS_CONFIRMATION -> 45;
            case CONDITIONAL -> 65;
            case ELIGIBLE -> 70;
            case INELIGIBLE, CONFLICTING_EVIDENCE -> 0;
        };
        if (job.jobFamily()!=JobFamily.OTHER) score+=10;
        if (candidate.acceptedEmploymentTypes().contains(job.employmentType())) score+=10;
        if (candidate.preferredLocations().stream().anyMatch(p -> job.location()!=null && job.location().contains(p))) score+=10;
        return Math.min(score,100);
    }
    private String explainScore(EligibilityStatus status,int score) { return "hardEligibility="+status+", basicMatchScore="+score; }
    /** 资格与分层是两个独立结论：§6.1 的硬判定不因所在池而改变，§3 的分池也不因资格而改变。 */
    public record DecisionResult(EligibilityAssessment assessment, Opportunity opportunity, OpportunityTierAssessment tier) {}
}
