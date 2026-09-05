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
    private final OpportunityScorer scorer;

    public CareerDecisionService(RepositoryPorts.CandidateProfiles candidates, RepositoryPorts.JobPostings jobs, RepositoryPorts.Organizations organizations, RepositoryPorts.EligibilityAssessments assessments, RepositoryPorts.Opportunities opportunities, EligibilityEvaluator evaluator, OpportunityTierClassifier tierClassifier, OpportunityScorer scorer) {
        this.candidates=candidates; this.jobs=jobs; this.organizations=organizations; this.assessments=assessments; this.opportunities=opportunities; this.evaluator=evaluator; this.tierClassifier=tierClassifier; this.scorer=scorer;
    }

    public DecisionResult assess(UUID candidateId, UUID jobId, Instant now) {
        var candidate=candidates.findById(candidateId).orElseThrow(() -> new IllegalArgumentException("Candidate not found: "+candidateId));
        var job=jobs.findById(jobId).orElseThrow(() -> new IllegalArgumentException("Job not found: "+jobId));
        var evaluated=evaluator.evaluate(candidate,job,now);
        var existingAssessment=assessments.findAll().stream().filter(value->value.candidateProfileId().equals(candidateId)&&value.jobPostingId().equals(jobId)&&value.evaluatorVersion().equals(EligibilityEvaluator.VERSION)).findFirst();
        var assessment=assessments.save(existingAssessment.map(value->new EligibilityAssessment(value.id(),candidateId,jobId,evaluated.status(),evaluated.ruleResults(),evaluated.requiredConfirmations(),evaluated.evidenceIds(),evaluated.evaluatorVersion(),now)).orElse(evaluated));
        var organization=organizations.findById(job.organizationId()).orElseThrow(() -> new IllegalArgumentException("Organization not found: "+job.organizationId()));
        var tier=tierClassifier.classify(job,organization);
        var scorecard=scorer.score(candidate,job,organization,assessment,tier);
        var existingOpportunity=opportunities.findAll().stream().filter(value->value.candidateProfileId().equals(candidateId)&&value.jobPostingId().equals(jobId)).findFirst();
        var opportunity=opportunities.save(existingOpportunity.map(value->new Opportunity(value.id(),candidate.id(),job.id(),assessment.id(),value.status(),scorecard,scorecard.gradeRationale(),value.createdAt(),now)).orElseGet(()->new Opportunity(UUID.randomUUID(),candidate.id(),job.id(),assessment.id(),OpportunityStatus.NEW,scorecard,scorecard.gradeRationale(),now,now)));
        return new DecisionResult(assessment,opportunity,tier);
    }

    /** 资格与分层是两个独立结论：§6.1 的硬判定不因所在池而改变，§3 的分池也不因资格而改变。 */
    public record DecisionResult(EligibilityAssessment assessment, Opportunity opportunity, OpportunityTierAssessment tier) {}
}
