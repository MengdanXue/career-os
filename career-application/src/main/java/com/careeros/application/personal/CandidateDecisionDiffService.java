package com.careeros.application.personal;

import static com.careeros.application.DecisionPorts.*;
import static com.careeros.domain.DomainEnums.*;

import com.careeros.application.CandidateProfileService;
import com.careeros.application.RepositoryPorts;
import com.careeros.domain.EligibilityAssessment.RuleResult;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

public final class CandidateDecisionDiffService {
    private final RepositoryPorts.CandidateProfiles candidates;
    private final DecisionSnapshots snapshots;
    private final DecisionAssessor assessor;

    public CandidateDecisionDiffService(RepositoryPorts.CandidateProfiles candidates,
                                        DecisionSnapshots snapshots,
                                        DecisionAssessor assessor) {
        this.candidates = Objects.requireNonNull(candidates);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.assessor = Objects.requireNonNull(assessor);
    }

    public DecisionChangeSummary recompute(UUID candidateId, String previousProfileVersion, LocalDate asOf) {
        Objects.requireNonNull(candidateId);
        if (previousProfileVersion == null || previousProfileVersion.isBlank()) {
            throw new IllegalArgumentException("previousProfileVersion is required");
        }
        Objects.requireNonNull(asOf);
        var currentCandidate = candidates.findById(candidateId).orElseThrow(() ->
            new CandidateProfileService.CandidateProfileNotFoundException("Candidate not found: " + candidateId));
        var previous = latestByJob(snapshots.findByCandidateAndProfileVersion(candidateId, previousProfileVersion));
        if (previous.isEmpty()) {
            return new DecisionChangeSummary(candidateId, previousProfileVersion,
                currentCandidate.profileVersion(), asOf, false,
                "没有找到该资料版本的历史岗位结论，无法可靠计算变化。", null, null, null, List.of());
        }

        var current = new LinkedHashMap<UUID, DecisionBundle>();
        var assessedAt = asOf.atStartOfDay(ZoneOffset.UTC).toInstant();
        previous.keySet().forEach(jobId -> current.put(jobId, assessor.assess(candidateId, jobId, assessedAt)));

        int newlyEligible = 0;
        int resolvedUncertainty = 0;
        int newlyIneligible = 0;
        var affected = new ArrayList<DecisionChangeSummary.AffectedJob>();
        for (var entry : previous.entrySet()) {
            var before = entry.getValue();
            var after = current.get(entry.getKey());
            var beforeStatus = before.eligibility().status();
            var afterStatus = after.eligibility().status();
            if (!isEligible(beforeStatus) && isEligible(afterStatus)) newlyEligible++;
            if (beforeStatus == EligibilityStatus.UNCERTAIN && afterStatus != EligibilityStatus.UNCERTAIN) {
                resolvedUncertainty++;
            }
            if (!isIneligible(beforeStatus) && isIneligible(afterStatus)) newlyIneligible++;
            var reasons = reasons(before, after);
            if (!reasons.isEmpty()) {
                affected.add(new DecisionChangeSummary.AffectedJob(entry.getKey(),
                    after.jobContext().job().title(), after.jobContext().organization().name(),
                    beforeStatus, afterStatus, reasons, "/opportunities/" + entry.getKey()));
            }
        }
        affected.sort(Comparator.comparing(DecisionChangeSummary.AffectedJob::organizationName,
            Comparator.nullsLast(String::compareTo)).thenComparing(DecisionChangeSummary.AffectedJob::title,
            Comparator.nullsLast(String::compareTo)).thenComparing(DecisionChangeSummary.AffectedJob::jobId));
        return new DecisionChangeSummary(candidateId, previousProfileVersion,
            currentCandidate.profileVersion(), asOf, true, null,
            newlyEligible, resolvedUncertainty, newlyIneligible, affected);
    }

    private static Map<UUID, DecisionBundle> latestByJob(List<DecisionBundle> values) {
        var result = new LinkedHashMap<UUID, DecisionBundle>();
        values.stream().sorted(Comparator.comparing((DecisionBundle value) ->
                value.decision().assessedAt()).reversed())
            .forEach(value -> result.putIfAbsent(value.decision().jobPostingId(), value));
        return result;
    }

    private static List<String> reasons(DecisionBundle before, DecisionBundle after) {
        var reasons = new ArrayList<String>();
        var rules = EnumSet.noneOf(RuleType.class);
        rules.addAll(before.eligibility().ruleResults().keySet());
        rules.addAll(after.eligibility().ruleResults().keySet());
        for (var rule : rules) {
            RuleResult oldValue = before.eligibility().ruleResults().get(rule);
            RuleResult newValue = after.eligibility().ruleResults().get(rule);
            if (Objects.equals(oldValue, newValue)) continue;
            String oldText = oldValue == null ? "无结论" : status(oldValue.status());
            String newText = newValue == null ? "无结论" : status(newValue.status());
            String detail = newValue == null ? "" : "（" + newValue.explanation() + "）";
            reasons.add(rule(rule) + "：" + oldText + " → " + newText + detail);
        }
        if (reasons.isEmpty() && before.eligibility().status() != after.eligibility().status()) {
            reasons.add("总体资格：" + status(before.eligibility().status()) + " → "
                + status(after.eligibility().status()));
        }
        return List.copyOf(reasons);
    }

    private static boolean isEligible(EligibilityStatus value) {
        return value == EligibilityStatus.ELIGIBLE || value == EligibilityStatus.LIKELY_ELIGIBLE;
    }

    private static boolean isIneligible(EligibilityStatus value) {
        return value == EligibilityStatus.INELIGIBLE || value == EligibilityStatus.LIKELY_INELIGIBLE;
    }

    private static String status(EligibilityStatus value) {
        return switch (value) {
            case ELIGIBLE, LIKELY_ELIGIBLE -> "可报";
            case UNCERTAIN -> "待确认";
            case INELIGIBLE, LIKELY_INELIGIBLE -> "不可报";
        };
    }

    private static String rule(RuleType value) {
        return switch (value) {
            case AGE -> "年龄";
            case EDUCATION -> "学历";
            case EXACT_MAJOR -> "专业";
            case GRADUATE_YEAR -> "毕业届别";
            case EXPERIENCE -> "工作经历";
            case PROFESSIONAL_TITLE -> "职称";
            case OTHER -> "其他条件";
        };
    }
}
