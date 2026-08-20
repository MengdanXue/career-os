package com.careeros.application;

import com.careeros.application.DecisionPorts.JobContexts;
import com.careeros.application.JobAdmissionPorts.JobAdmissions;
import com.careeros.domain.DomainEnums.*;
import com.careeros.domain.CandidateFacts;
import com.careeros.domain.CandidateProfile;
import com.careeros.domain.EligibilityAssessment;
import com.careeros.domain.EligibilityEvaluator;
import com.careeros.domain.FitEvaluator;
import com.careeros.domain.JobAdmission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class CandidateMatchService {
    private final RepositoryPorts.CandidateProfiles candidates;
    private final RepositoryPorts.CandidateFactConfirmations candidateFacts;
    private final JobContexts jobs;
    private final JobAdmissions admissions;
    private final EligibilityEvaluator eligibility;
    private final FitEvaluator fit;

    public CandidateMatchService(
        RepositoryPorts.CandidateProfiles candidates,
        RepositoryPorts.CandidateFactConfirmations candidateFacts,
        JobContexts jobs,
        JobAdmissions admissions,
        EligibilityEvaluator eligibility,
        FitEvaluator fit
    ) {
        this.candidates=Objects.requireNonNull(candidates); this.candidateFacts=Objects.requireNonNull(candidateFacts);
        this.jobs=Objects.requireNonNull(jobs); this.admissions=Objects.requireNonNull(admissions);
        this.eligibility=Objects.requireNonNull(eligibility); this.fit=Objects.requireNonNull(fit);
    }

    public MatchPage list(UUID candidateId, MatchQuery query, Instant now) {
        Objects.requireNonNull(candidateId, "candidateId"); Objects.requireNonNull(query, "query");
        Objects.requireNonNull(now, "now");
        if (query.page() < 0) throw new IllegalArgumentException("page must not be negative");
        if (query.size() < 1 || query.size() > 100) throw new IllegalArgumentException("size must be between 1 and 100");
        CandidateProfile candidate = candidates.findById(candidateId)
            .orElseThrow(() -> new DecisionExceptions.CandidateNotFoundException("Candidate not found: " + candidateId));
        CandidateFacts facts = CandidateFacts.resolve(candidate, candidateFacts.findByCandidateId(candidateId));
        var targetAdmissions = new LinkedHashMap<UUID, JobAdmission>();
        admissions.findCandidateMatches().stream().filter(CandidateMatchService::isTarget)
            .forEach(admission -> targetAdmissions.put(admission.jobPostingId(), admission));
        List<CandidateMatch> matches = jobs.findActiveByJobIds(targetAdmissions.keySet()).stream()
            .map(context -> match(candidate, facts, context,
                targetAdmissions.get(context.job().id()), now))
            .filter(value -> value.eligibilityStatus() != EligibilityStatus.INELIGIBLE
                && value.eligibilityStatus() != EligibilityStatus.LIKELY_INELIGIBLE)
            .sorted(order()).toList();
        int from = (int)Math.min((long)query.page() * query.size(), matches.size());
        int to = Math.min(from + query.size(), matches.size());
        return new MatchPage(matches.subList(from, to), query.page(), query.size(), matches.size());
    }

    private CandidateMatch match(
        CandidateProfile candidate, CandidateFacts facts, DecisionPorts.JobContext context,
        JobAdmission admission, Instant now
    ) {
        var eligibilityResult = eligibility.evaluate(
            candidate, facts, context.job(), context.contentFingerprint(), now);
        var fitResult = fit.evaluate(
            candidate, facts, context.job(), context.organization(), context.contentFingerprint(), now);
        boolean identityConfirmed = context.job().employmentType() != EmploymentType.UNKNOWN;
        var warnings = new LinkedHashSet<String>();
        if (!identityConfirmed) warnings.add("用工身份待官方证据确认");
        eligibilityResult.ruleResults().values().stream()
            .filter(result -> result.status() != EligibilityStatus.ELIGIBLE)
            .map(EligibilityAssessment.RuleResult::explanation).forEach(warnings::add);
        return new CandidateMatch(
            context.job().id(), context.job().title(), context.organization().name(), context.job().location(),
            eligibilityResult.status(), fitResult.score(), fitResult.coveragePercent(),
            context.job().employmentType(), identityConfirmed, admission.reasonCodes(), List.copyOf(warnings),
            context.job().sourceUrl(), context.contentFingerprint());
    }

    private static boolean isTarget(JobAdmission admission) {
        return admission.dataQualityStatus() == DataQualityStatus.VERIFIED
            && admission.targetScopeStatus() != TargetScopeStatus.EXCLUDED
            && admission.reasonCodes().contains(JobAdmissionReason.TARGET_TECHNICAL_ROLE);
    }

    private static Comparator<CandidateMatch> order() {
        return Comparator.comparingInt((CandidateMatch value) -> severity(value.eligibilityStatus()))
            .thenComparing(Comparator.comparing(CandidateMatch::employmentIdentityConfirmed).reversed())
            .thenComparingInt(value -> value.warnings().size())
            .thenComparing(Comparator.comparingInt(CandidateMatch::fitScore).reversed())
            .thenComparing(CandidateMatch::organizationName, Comparator.nullsLast(String::compareTo))
            .thenComparing(CandidateMatch::jobTitle)
            .thenComparing(CandidateMatch::jobId);
    }

    private static int severity(EligibilityStatus value) {
        return switch (value) {
            case ELIGIBLE -> 0;
            case LIKELY_ELIGIBLE -> 1;
            case UNCERTAIN -> 2;
            case LIKELY_INELIGIBLE -> 3;
            case INELIGIBLE -> 4;
        };
    }

    public record MatchQuery(int page, int size) {}
    public record MatchPage(List<CandidateMatch> items, int page, int size, long total) {
        public MatchPage { items = List.copyOf(items); }
    }
    public record CandidateMatch(
        UUID jobId, String jobTitle, String organizationName, String location,
        EligibilityStatus eligibilityStatus, int fitScore, int coveragePercent,
        EmploymentType employmentType, boolean employmentIdentityConfirmed,
        Set<JobAdmissionReason> admissionReasons, List<String> warnings,
        String sourceUrl, String jobContentFingerprint
    ) {
        public CandidateMatch {
            admissionReasons = Set.copyOf(admissionReasons);
            warnings = List.copyOf(warnings);
        }
    }
}
