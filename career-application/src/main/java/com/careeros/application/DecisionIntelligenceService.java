package com.careeros.application;

import static com.careeros.application.DecisionPorts.*;
import static com.careeros.domain.DomainEnums.*;

import com.careeros.application.JobAdmissionPorts.JobAdmissions;
import com.careeros.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class DecisionIntelligenceService implements DecisionAssessor {
    public static final String VERSION = "decision-v3-qualification-cutoff";
    private final RepositoryPorts.CandidateProfiles candidates;
    private final RepositoryPorts.CandidateFactConfirmations candidateFacts;
    private final RepositoryPorts.EligibilityAssessments eligibilityAssessments;
    private final JobContexts jobContexts;
    private final OrganizationStabilityFacts stabilityFacts;
    private final DecisionSnapshots snapshots;
    private final JobAdmissions admissions;
    private final JobAdmissionPorts.JobFieldEvidence fieldEvidence;
    private final DecisionInputLock inputLock;
    private final EligibilityEvaluator eligibilityEvaluator;
    private final FitEvaluator fitEvaluator;
    private final StabilityEvaluator stabilityEvaluator;

    public DecisionIntelligenceService(
        RepositoryPorts.CandidateProfiles candidates,
        RepositoryPorts.CandidateFactConfirmations candidateFacts,
        RepositoryPorts.EligibilityAssessments eligibilityAssessments,
        JobContexts jobContexts,
        OrganizationStabilityFacts stabilityFacts,
        DecisionSnapshots snapshots,
        JobAdmissions admissions,
        JobAdmissionPorts.JobFieldEvidence fieldEvidence,
        DecisionInputLock inputLock,
        EligibilityEvaluator eligibilityEvaluator,
        FitEvaluator fitEvaluator,
        StabilityEvaluator stabilityEvaluator
    ) {
        this.candidates = Objects.requireNonNull(candidates);
        this.candidateFacts = Objects.requireNonNull(candidateFacts);
        this.eligibilityAssessments = Objects.requireNonNull(eligibilityAssessments);
        this.jobContexts = Objects.requireNonNull(jobContexts);
        this.stabilityFacts = Objects.requireNonNull(stabilityFacts);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.admissions = Objects.requireNonNull(admissions);
        this.fieldEvidence = Objects.requireNonNull(fieldEvidence);
        this.inputLock = Objects.requireNonNull(inputLock);
        this.eligibilityEvaluator = Objects.requireNonNull(eligibilityEvaluator);
        this.fitEvaluator = Objects.requireNonNull(fitEvaluator);
        this.stabilityEvaluator = Objects.requireNonNull(stabilityEvaluator);
    }

    public DecisionBundle assess(UUID candidateId, UUID jobId, Instant now) {
        return inputLock.execute(lockFingerprint(candidateId, jobId), () -> {
            var candidate = candidate(candidateId);
            var facts = CandidateFacts.resolve(candidate, candidateFacts.findByCandidateId(candidateId));
            var context = lockedContext(jobId);
            requireDecisionReady(jobId, context);
            var input = input(candidate, context);
            return snapshots.findByInput(input).orElseGet(() -> evaluate(input, candidate, facts, context, now));
        });
    }

    public DecisionBundle current(UUID candidateId, UUID jobId) {
        return inputLock.execute(lockFingerprint(candidateId, jobId), () -> {
            var candidate = candidate(candidateId);
            var context = lockedContext(jobId);
            requireDecisionReady(jobId, context);
            return snapshots.findByInput(input(candidate, context))
                .orElseThrow(() -> new DecisionExceptions.DecisionNotFoundException(
                    "Decision not found for the current candidate and job versions"));
        });
    }

    private CandidateProfile candidate(UUID candidateId) {
        return candidates.findById(candidateId)
            .orElseThrow(() -> new DecisionExceptions.CandidateNotFoundException("Candidate not found: " + candidateId));
    }

    private JobContext lockedContext(UUID jobId) {
        return jobContexts.findByJobIdForUpdate(jobId)
            .orElseThrow(() -> new DecisionExceptions.JobNotFoundException("Job not found: " + jobId));
    }

    private void requireDecisionReady(UUID jobId, JobContext context) {
        var admission = admissions.findByJobIdForUpdate(jobId);
        if (admission.map(value -> value.admits(context.job())).orElse(false)) {
            return;
        }
        if (context.job().employmentType() == EmploymentType.UNKNOWN
            && admission.map(JobAdmission::admitted).orElse(false)) {
            throw new DecisionExceptions.JobNotAdmittedException(
                "Job employment identity has not been verified: " + jobId);
        }
        throw new DecisionExceptions.JobNotAdmittedException(
            "Job has not passed evidence admission: " + jobId);
    }

    private static DecisionInputKey input(CandidateProfile candidate, JobContext context) {
        return new DecisionInputKey(candidate.id(), context.job().id(), candidate.profileVersion(),
            context.contentFingerprint(), evaluatorIdentity(context));
    }

    private static String lockFingerprint(UUID candidateId, UUID jobId) {
        String value = candidateId + "\n" + jobId;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private DecisionBundle evaluate(DecisionInputKey input, CandidateProfile candidate, CandidateFacts facts, JobContext context, Instant now) {
        LocalDate qualificationAsOf = context.event().applicationEndsOn();
        // 官方来源在某个字段上互相矛盾时，依赖该字段的规则不产出判定：手里的岗位要求
        // 本身就不可信，此时说"满足"或"不满足"都是在替官方做决定。
        var jobFieldEvidence = fieldEvidence.coverage(context.job().id());
        var conflictingFields = jobFieldEvidence.conflictFields();
        var eligibility = eligibilityAssessments.save(eligibilityEvaluator.evaluate(candidate, facts,
            context.job(), context.contentFingerprint(), qualificationAsOf, now, input.evaluatorVersion(),
            conflictingFields,
            // 应届身份条款解析在招聘事件上，不在岗位上。为 null 表示公告里没有这类条款。
            context.graduateClause(),
            // §10.6：逐条结论挂到具体证据片段，而不是只指向整份公告。
            jobFieldEvidence.evidenceFragmentsByField()));
        var fit = fitEvaluator.evaluate(candidate, facts, context.job(), context.organization(),
            context.contentFingerprint(), qualificationAsOf, now, input.evaluatorVersion());
        var stabilityResult = stabilityEvaluator.evaluate(candidate, context.job(), context.organization(),
            stabilityFacts.findByOrganizationId(context.organization().id()), context.contentFingerprint(),
            now, input.evaluatorVersion());
        OpportunityTier tier = excluded(eligibility.status()) ? OpportunityTier.EXCLUDED : stabilityResult.tier();
        int coverage = Math.round((fit.coveragePercent() + stabilityResult.assessment().coveragePercent()) / 2f);
        RecommendationStatus recommendation = recommendation(eligibility.status(), tier, fit.score(), coverage);
        var decision = new DecisionAssessment(
            UUID.randomUUID(), candidate.id(), context.job().id(), eligibility.id(), fit.id(), stabilityResult.assessment().id(),
            eligibility.status(), tier, recommendation, fit.score(), stabilityResult.assessment().score(), coverage,
            input.evaluatorVersion(), candidate.profileVersion(), context.contentFingerprint(), now
        );
        return snapshots.save(input, new DecisionBundle(eligibility, fit, stabilityResult.assessment(), decision, context));
    }

    private static String evaluatorIdentity(JobContext context) {
        LocalDate cutoff = context.event().applicationEndsOn();
        return VERSION + "|" + EligibilityEvaluator.VERSION
            + "@cutoff=" + (cutoff == null ? "unknown" : cutoff);
    }

    /** 只有明确不满足才排除。证据不足不是排除理由，是复核理由。 */
    private static boolean excluded(EligibilityStatus status) { return status == EligibilityStatus.INELIGIBLE; }

    /**
     * 产品需求 §6.1：只有 ELIGIBLE 和经用户确认后的 CONDITIONAL 才能进入“建议报名”。
     * CONDITIONAL 在这里一律走 REVIEW——用户还没确认那件待完成的事，系统不能替他认定它会发生。
     */
    private static RecommendationStatus recommendation(EligibilityStatus eligibility, OpportunityTier tier, int fitScore, int coverage) {
        if (excluded(eligibility)) return RecommendationStatus.EXCLUDED;
        if (eligibility != EligibilityStatus.ELIGIBLE || coverage < 50 || tier == OpportunityTier.T3) {
            return RecommendationStatus.REVIEW;
        }
        return fitScore >= 60 ? RecommendationStatus.RECOMMENDED : RecommendationStatus.NOT_RECOMMENDED;
    }
}
