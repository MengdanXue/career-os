package com.careeros.application;

import com.careeros.domain.*;
import com.careeros.domain.DomainEnums.*;
import java.time.Instant;
import java.util.UUID;

public final class CareerDecisionService {
    private final RepositoryPorts.CandidateProfiles candidates;
    private final RepositoryPorts.JobPostings jobs;
    private final RepositoryPorts.EligibilityAssessments assessments;
    private final RepositoryPorts.Opportunities opportunities;
    private final EligibilityEvaluator evaluator;

    public CareerDecisionService(RepositoryPorts.CandidateProfiles candidates, RepositoryPorts.JobPostings jobs, RepositoryPorts.EligibilityAssessments assessments, RepositoryPorts.Opportunities opportunities, EligibilityEvaluator evaluator) {
        this.candidates=candidates; this.jobs=jobs; this.assessments=assessments; this.opportunities=opportunities; this.evaluator=evaluator;
    }

    public DecisionResult assess(UUID candidateId, UUID jobId, Instant now) {
        var candidate=candidates.findById(candidateId).orElseThrow(() -> new IllegalArgumentException("Candidate not found: "+candidateId));
        var job=jobs.findById(jobId).orElseThrow(() -> new IllegalArgumentException("Job not found: "+jobId));
        var evaluated=evaluator.evaluate(candidate,job,now);
        var existingAssessment=assessments.findAll().stream().filter(value->value.candidateProfileId().equals(candidateId)&&value.jobPostingId().equals(jobId)&&value.evaluatorVersion().equals(EligibilityEvaluator.VERSION)).findFirst();
        var assessment=assessments.save(existingAssessment.map(value->new EligibilityAssessment(value.id(),candidateId,jobId,evaluated.status(),evaluated.ruleResults(),evaluated.evidenceIds(),evaluated.evaluatorVersion(),now)).orElse(evaluated));
        int score=score(candidate,job,assessment.status());
        var existingOpportunity=opportunities.findAll().stream().filter(value->value.candidateProfileId().equals(candidateId)&&value.jobPostingId().equals(jobId)).findFirst();
        var opportunity=opportunities.save(existingOpportunity.map(value->new Opportunity(value.id(),candidate.id(),job.id(),assessment.id(),value.status(),score,explainScore(assessment.status(),score),value.createdAt(),now)).orElseGet(()->new Opportunity(UUID.randomUUID(),candidate.id(),job.id(),assessment.id(),OpportunityStatus.NEW,score,explainScore(assessment.status(),score),now,now)));
        return new DecisionResult(assessment,opportunity);
    }

    int score(CandidateProfile candidate, JobPosting job, EligibilityStatus status) {
        if (status==EligibilityStatus.INELIGIBLE) return 0;
        int score=status==EligibilityStatus.UNCERTAIN ? 45 : status==EligibilityStatus.LIKELY_ELIGIBLE ? 65 : 70;
        if (job.jobFamily()!=JobFamily.OTHER) score+=10;
        if (candidate.acceptedEmploymentTypes().contains(job.employmentType())) score+=10;
        if (candidate.preferredLocations().stream().anyMatch(p -> job.location()!=null && job.location().contains(p))) score+=10;
        return Math.min(score,100);
    }
    private String explainScore(EligibilityStatus status,int score) { return "hardEligibility="+status+", basicMatchScore="+score; }
    public record DecisionResult(EligibilityAssessment assessment, Opportunity opportunity) {}
}
