package com.careeros.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record GraduateEligibilityRule(
    int recruitmentYear,
    Set<Integer> explicitGraduationYears,
    Set<CohortScope> cohorts,
    boolean includesOverseasGraduates,
    RequirementTiming degreeTiming,
    LocalDate degreeDeadline,
    RequirementTiming credentialTiming,
    LocalDate credentialDeadline,
    boolean requiresNoEmployer,
    boolean restrictsSocialInsurance,
    String rawText,
    EvidenceState evidenceState
) {
    public GraduateEligibilityRule {
        if (recruitmentYear < 2000 || recruitmentYear > 2100) {
            throw new IllegalArgumentException("recruitmentYear is invalid");
        }
        explicitGraduationYears = explicitGraduationYears == null
            ? Set.of() : Set.copyOf(explicitGraduationYears);
        cohorts = cohorts == null ? Set.of() : Set.copyOf(cohorts);
        degreeTiming = Objects.requireNonNull(degreeTiming);
        credentialTiming = Objects.requireNonNull(credentialTiming);
        rawText = rawText == null ? "" : rawText;
        evidenceState = Objects.requireNonNull(evidenceState);
    }

    public Set<Integer> acceptedYearsFor(int targetYear) {
        if (targetYear < 2000 || targetYear > 2100) {
            throw new IllegalArgumentException("targetYear is invalid");
        }
        if (evidenceState != EvidenceState.CONFIRMED || cohorts.contains(CohortScope.UNRESTRICTED)) {
            return Set.of();
        }
        return cohorts.stream()
            .map(scope -> switch (scope) {
                case CURRENT_YEAR -> targetYear;
                case PREVIOUS_YEAR -> targetYear - 1;
                case TWO_YEARS_PRIOR -> targetYear - 2;
                case UNRESTRICTED -> throw new IllegalStateException("unrestricted cohort handled above");
            })
            .collect(Collectors.toUnmodifiableSet());
    }

    public static GraduateEligibilityRule fromExplicitYears(
        int recruitmentYear,
        Set<Integer> years,
        boolean includesOverseasGraduates,
        String rawText
    ) {
        Set<Integer> safeYears = years == null ? Set.of() : Set.copyOf(years);
        Set<Integer> offsets = safeYears.stream()
            .map(year -> recruitmentYear - year)
            .collect(Collectors.toUnmodifiableSet());
        boolean inProjectionWindow = !offsets.isEmpty()
            && offsets.stream().allMatch(offset -> offset >= 0 && offset <= 2);
        int minimumOffset = offsets.stream().mapToInt(Integer::intValue).min().orElse(0);
        int maximumOffset = offsets.stream().mapToInt(Integer::intValue).max().orElse(-1);
        boolean contiguous = inProjectionWindow && offsets.size() == maximumOffset - minimumOffset + 1;
        boolean projectable = inProjectionWindow && contiguous;
        Set<CohortScope> scopes = projectable
            ? offsets.stream().map(GraduateEligibilityRule::cohortForOffset).collect(Collectors.toUnmodifiableSet())
            : Set.of();

        return new GraduateEligibilityRule(
            recruitmentYear,
            safeYears,
            scopes,
            includesOverseasGraduates,
            RequirementTiming.UNSPECIFIED,
            null,
            RequirementTiming.UNSPECIFIED,
            null,
            false,
            false,
            rawText,
            projectable ? EvidenceState.CONFIRMED : EvidenceState.REVIEW_REQUIRED);
    }

    private static CohortScope cohortForOffset(int offset) {
        return switch (offset) {
            case 0 -> CohortScope.CURRENT_YEAR;
            case 1 -> CohortScope.PREVIOUS_YEAR;
            case 2 -> CohortScope.TWO_YEARS_PRIOR;
            default -> throw new IllegalArgumentException("graduation year offset is not projectable: " + offset);
        };
    }

    public enum CohortScope {
        CURRENT_YEAR,
        PREVIOUS_YEAR,
        TWO_YEARS_PRIOR,
        UNRESTRICTED
    }

    public enum EvidenceState {
        CONFIRMED,
        NOT_PUBLISHED,
        NOT_REQUIRED,
        NOT_COLLECTED,
        PARSE_FAILED,
        REVIEW_REQUIRED,
        UNKNOWN
    }

    public enum RequirementTiming {
        APPLICATION,
        QUALIFICATION_REVIEW,
        APPOINTMENT,
        REPORTING,
        UNSPECIFIED,
        NOT_REQUIRED
    }
}
