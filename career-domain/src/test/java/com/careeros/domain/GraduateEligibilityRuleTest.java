package com.careeros.domain;

import static com.careeros.domain.GraduateEligibilityRule.CohortScope.CURRENT_YEAR;
import static com.careeros.domain.GraduateEligibilityRule.CohortScope.PREVIOUS_YEAR;
import static com.careeros.domain.GraduateEligibilityRule.CohortScope.TWO_YEARS_PRIOR;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class GraduateEligibilityRuleTest {

    @Test
    void convertsExplicitHistoricalYearsIntoTargetYearRelativeCohorts() {
        var rule = GraduateEligibilityRule.fromExplicitYears(
            2026,
            Set.of(2024, 2025, 2026),
            true,
            "2024年、2025年和2026年普通高校毕业生，含同期毕业的留学回国人员");

        assertThat(rule.cohorts()).containsExactlyInAnyOrder(CURRENT_YEAR, PREVIOUS_YEAR, TWO_YEARS_PRIOR);
        assertThat(rule.includesOverseasGraduates()).isTrue();
        assertThat(rule.acceptedYearsFor(2027)).containsExactlyInAnyOrder(2025, 2026, 2027);
        assertThat(rule.evidenceState()).isEqualTo(GraduateEligibilityRule.EvidenceState.CONFIRMED);
    }

    @Test
    void refusesToProjectNonContiguousOrFutureGraduationYears() {
        var rule = GraduateEligibilityRule.fromExplicitYears(
            2026,
            Set.of(2023, 2026, 2028),
            false,
            "2023届、2026届、2028届毕业生");

        assertThat(rule.evidenceState()).isEqualTo(GraduateEligibilityRule.EvidenceState.REVIEW_REQUIRED);
        assertThat(rule.cohorts()).isEmpty();
        assertThat(rule.acceptedYearsFor(2027)).isEmpty();
    }
}
