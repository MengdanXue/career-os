package com.careeros.application;

import static com.careeros.application.DecisionPorts.*;
import static com.careeros.domain.DomainEnums.*;

import com.careeros.application.JobAdmissionPorts.JobAdmissions;
import com.careeros.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class DecisionIntelligenceService implements DecisionAssessor {
    public static final String VERSION = "decision-v1";
    private final RepositoryPorts.CandidateProfiles candidates;
    private final RepositoryPorts.EligibilityAssessments eligibilityAssessments;
    private final JobContexts jobContexts;
    private final OrganizationStabilityFacts stabilityFacts;
    private final DecisionSnapshots snapshots;
    private final JobAdmissions admissions;
    private final DecisionInputLock inputLock;
    private final EligibilityEvaluator eligibilityEvaluator;
    private final FitEvaluator fitEvaluator;
    private final StabilityEvaluator stabilityEvaluator;

    public DecisionIntelligenceService(
        RepositoryPorts.CandidateProfiles candidates,
        RepositoryPorts.EligibilityAssessments eligibilityAssessments,
        JobContexts jobContexts,
        OrganizationStabilityFacts stabilityFacts,
        DecisionSnapshots snapshots,
        JobAdmissions admissions,
        DecisionInputLock inputLock,
        EligibilityEvaluator eligibilityEvaluator,
        FitEvaluator fitEvaluator,
        StabilityEvaluator stabilityEvaluator
    ) {
        this.candidates = Objects.requireNonNull(candidates);
        this.eligibilityAssessments = Objects.requireNonNull(eligibilityAssessments);
        this.jobContexts = Objects.requireNonNull(jobContexts);
        this.stabilityFacts = Objects.requireNonNull(stabilityFacts);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.admissions = Objects.requireNonNull(admissions);
        this.inputLock = Objects.requireNonNull(inputLock);
        this.eligibilityEvaluator = Objects.requireNonNull(eligibilityEvaluator);
        this.fitEvaluator = Objects.requireNonNull(fitEvaluator);
        this.stabilityEvaluator = Objects.requireNonNull(stabilityEvaluator);
    }

    public DecisionBundle assess(UUID candidateId, UUID jobId, Instant now) {
        var candidate = candidate(candidateId);
        var context = context(jobId);
        requireAdmitted(jobId);
        var input = input(candidate, context);
        return inputLock.execute(inputFingerprint(input),
            () -> snapshots.findByInput(input).orElseGet(() -> evaluate(input, candidate, context, now)));
    }

    public DecisionBundle current(UUID candidateId, UUID jobId) {
        var candidate = candidate(candidateId);
        var context = context(jobId);
        requireAdmitted(jobId);
        return snapshots.findByInput(input(candidate, context))
            .orElseThrow(() -> new DecisionExceptions.DecisionNotFoundException(
                "Decision not found for the current candidate and job versions"));
    }

    private CandidateProfile candidate(UUID candidateId) {
        return candidates.findById(candidateId)
            .orElseThrow(() -> new DecisionExceptions.CandidateNotFoundException("Candidate not found: " + candidateId));
    }

    private JobContext context(UUID jobId) {
        return jobContexts.findByJobId(jobId)
            .orElseThrow(() -> new DecisionExceptions.JobNotFoundException("Job not found: " + jobId));
    }

    private void requireAdmitted(UUID jobId) {
        if (admissions.findByJobId(jobId).map(value -> value.admitted()).orElse(false)) {
            return;
        }
        throw new DecisionExceptions.JobNotAdmittedException(
            "Job has not passed evidence admission: " + jobId);
    }

    private static DecisionInputKey input(CandidateProfile candidate, JobContext context) {
        return new DecisionInputKey(candidate.id(), context.job().id(), candidate.profileVersion(), context.contentFingerprint(), VERSION);
    }

    private static String inputFingerprint(DecisionInputKey input) {
        String value = input.candidateProfileId() + "\n" + input.jobPostingId() + "\n"
            + input.profileVersion() + "\n" + input.jobContentFingerprint() + "\n" + input.evaluatorVersion();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private DecisionBundle evaluate(DecisionInputKey input, CandidateProfile candidate, JobContext context, Instant now) {
        var eligibility = eligibilityAssessments.save(eligibilityEvaluator.evaluate(candidate, context.job(), context.contentFingerprint(), now));
        var fit = fitEvaluator.evaluate(candidate, context.job(), context.organization(), context.contentFingerprint(), now);
        var stabilityResult = stabilityEvaluator.evaluate(candidate, context.job(), context.organization(), stabilityFacts.findByOrganizationId(context.organization().id()), context.contentFingerprint(), now);
        OpportunityTier tier = excluded(eligibility.status()) ? OpportunityTier.EXCLUDED : stabilityResult.tier();
        int coverage = Math.round((fit.coveragePercent() + stabilityResult.assessment().coveragePercent()) / 2f);
        RecommendationStatus recommendation = recommendation(eligibility.status(), tier, fit.score(), coverage);
        var decision = new DecisionAssessment(
            UUID.randomUUID(), candidate.id(), context.job().id(), eligibility.id(), fit.id(), stabilityResult.assessment().id(),
            eligibility.status(), tier, recommendation, fit.score(), stabilityResult.assessment().score(), coverage,
            VERSION, candidate.profileVersion(), context.contentFingerprint(), now
        );
        return snapshots.save(input, new DecisionBundle(eligibility, fit, stabilityResult.assessment(), decision, context));
    }

    private static boolean excluded(EligibilityStatus status) { return status == EligibilityStatus.INELIGIBLE || status == EligibilityStatus.LIKELY_INELIGIBLE; }
    private static RecommendationStatus recommendation(EligibilityStatus eligibility, OpportunityTier tier, int fitScore, int coverage) {
        if (excluded(eligibility)) return RecommendationStatus.EXCLUDED;
        if (eligibility == EligibilityStatus.UNCERTAIN || coverage < 50 || tier == OpportunityTier.T3) return RecommendationStatus.REVIEW;
        return fitScore >= 60 ? RecommendationStatus.RECOMMENDED : RecommendationStatus.NOT_RECOMMENDED;
    }
}
