package com.careeros.domain;

import com.careeros.domain.DomainEnums.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EligibilityEvaluatorTest {
    private final EligibilityEvaluator evaluator = new EligibilityEvaluator();
    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

    @Test void ageIsPassBeforeAllPossibleBirthdays() {
        var result = evaluate(hangzhouCandidate(), job(32, LocalDate.of(2025, 8, 31), EducationLevel.MASTER, Set.of(), Set.of(), null));
        assertRule(result, RuleType.AGE, CriterionStatus.PASS);
    }

    @Test void partialBirthMonthInsideAgeBoundaryNeedsConfirmation() {
        var result = evaluate(hangzhouCandidate(), job(32, LocalDate.of(2025, 9, 1), EducationLevel.MASTER, Set.of(), Set.of(), null));
        assertRule(result, RuleType.AGE, CriterionStatus.UNKNOWN);
        assertThat(result.status()).isEqualTo(EligibilityStatus.NEEDS_CONFIRMATION);
        assertThat(result.requiredConfirmations()).anyMatch(value -> value.startsWith("AGE："));
    }

    @Test void ageFailsAfterAllPossibleBirthdays() {
        var result = evaluate(hangzhouCandidate(), job(32, LocalDate.of(2025, 10, 1), EducationLevel.MASTER, Set.of(), Set.of(), null));
        assertRule(result, RuleType.AGE, CriterionStatus.FAIL);
        assertThat(result.status()).isEqualTo(EligibilityStatus.INELIGIBLE);
    }

    @Test void educationUsesOrderedMinimumLevel() {
        var pass = evaluate(candidate(PartialDate.exact(LocalDate.of(1995, 1, 1)), EducationLevel.MASTER, Set.of(), 2020, 1), job(null, null, EducationLevel.BACHELOR, Set.of(), Set.of(), null));
        var fail = evaluate(candidate(PartialDate.exact(LocalDate.of(1995, 1, 1)), EducationLevel.BACHELOR, Set.of(), 2020, 1), job(null, null, EducationLevel.MASTER, Set.of(), Set.of(), null));
        assertRule(pass, RuleType.EDUCATION, CriterionStatus.PASS);
        assertRule(fail, RuleType.EDUCATION, CriterionStatus.FAIL);
    }

    @Test void missingJobEducationIsNotTreatedAsNoRequirement() {
        var result = evaluate(candidate(PartialDate.exact(LocalDate.of(1995, 1, 1)), EducationLevel.MASTER, Set.of(), 2020, 1), job(null, null, EducationLevel.UNKNOWN, Set.of(), Set.of(), null));
        assertRule(result, RuleType.EDUCATION, CriterionStatus.UNKNOWN);
        assertThat(result.status()).isEqualTo(EligibilityStatus.NEEDS_CONFIRMATION);
    }

    @Test void exactMajorDoesNotExpandBroadSynonyms() {
        var candidate = candidate(PartialDate.exact(LocalDate.of(1995, 1, 1)), EducationLevel.MASTER, Set.of("计算机科学与技术"), 2020, 5);
        assertRule(evaluate(candidate, job(null, null, EducationLevel.MASTER, Set.of("计算机科学与技术"), Set.of(), null)), RuleType.EXACT_MAJOR, CriterionStatus.PASS);
        assertRule(evaluate(candidate, job(null, null, EducationLevel.MASTER, Set.of("软件工程"), Set.of(), null)), RuleType.EXACT_MAJOR, CriterionStatus.FAIL);
    }

    /** 产品需求 §9：专业门类等同性必须留给人工，不能由引擎判成可报或不可报。 */
    @Test void broadMajorTaxonomyIsConditionalRatherThanDecided() {
        var candidate = candidate(PartialDate.exact(LocalDate.of(1995, 1, 1)), EducationLevel.MASTER, Set.of("计算机科学与技术"), 2020, 5);
        var result = evaluate(candidate, job(null, null, EducationLevel.MASTER, Set.of("计算机类"), Set.of(), null));
        assertRule(result, RuleType.EXACT_MAJOR, CriterionStatus.CONDITIONAL);
        assertThat(result.status()).isEqualTo(EligibilityStatus.CONDITIONAL);
        assertThat(result.requiredConfirmations()).anyMatch(value -> value.contains("专业分类表"));
    }

    @Test void graduateRestrictionIsDeterministicAndMissingDataIsUnknown() {
        var restricted = job(null, null, EducationLevel.MASTER, Set.of(), Set.of(2025, 2026), null);
        assertRule(evaluate(candidate(PartialDate.month(1995, 1), EducationLevel.MASTER, Set.of(), 2026, 0), restricted), RuleType.GRADUATE_YEAR, CriterionStatus.PASS);
        assertRule(evaluate(candidate(PartialDate.month(1995, 1), EducationLevel.MASTER, Set.of(), 2024, 0), restricted), RuleType.GRADUATE_YEAR, CriterionStatus.FAIL);
        assertRule(evaluate(candidate(PartialDate.month(1995, 1), EducationLevel.MASTER, Set.of(), null, 0), restricted), RuleType.GRADUATE_YEAR, CriterionStatus.UNKNOWN);
    }

    @Test void experienceBoundaryIncludesExactMinimum() {
        var requiresTwo = job(null, null, EducationLevel.MASTER, Set.of(), Set.of(), 2);
        assertRule(evaluate(candidate(PartialDate.month(1995, 1), EducationLevel.MASTER, Set.of(), 2020, 2), requiresTwo), RuleType.EXPERIENCE, CriterionStatus.PASS);
        assertRule(evaluate(candidate(PartialDate.month(1995, 1), EducationLevel.MASTER, Set.of(), 2020, 1), requiresTwo), RuleType.EXPERIENCE, CriterionStatus.FAIL);
        assertRule(evaluate(candidate(PartialDate.month(1995, 1), EducationLevel.MASTER, Set.of(), 2020, null), requiresTwo), RuleType.EXPERIENCE, CriterionStatus.UNKNOWN);
    }

    /** 公告没有设条件与"条件已确认满足"是两回事，前者不得把整体拉低。 */
    @Test void notApplicableRulesDoNotBlockEligibility() {
        var result = evaluate(candidate(PartialDate.exact(LocalDate.of(1995, 1, 1)), EducationLevel.MASTER, Set.of(), 2020, 3), job(null, null, EducationLevel.BACHELOR, Set.of(), Set.of(), null));
        assertThat(result.status()).isEqualTo(EligibilityStatus.ELIGIBLE);
        assertThat(result.requiredConfirmations()).isEmpty();
        assertRule(result, RuleType.EXACT_MAJOR, CriterionStatus.NOT_APPLICABLE);
    }

    /** 聚合 fail-closed：明确不满足压过未知，未知压过条件满足。 */
    @Test void aggregationPrefersTheMostBlockingOutcome() {
        assertThat(EligibilityEvaluator.aggregate(List.of(result(CriterionStatus.PASS), result(CriterionStatus.UNKNOWN), result(CriterionStatus.FAIL))))
            .isEqualTo(EligibilityStatus.INELIGIBLE);
        assertThat(EligibilityEvaluator.aggregate(List.of(result(CriterionStatus.CONDITIONAL), result(CriterionStatus.UNKNOWN))))
            .isEqualTo(EligibilityStatus.NEEDS_CONFIRMATION);
        assertThat(EligibilityEvaluator.aggregate(List.of(result(CriterionStatus.PASS), result(CriterionStatus.CONDITIONAL))))
            .isEqualTo(EligibilityStatus.CONDITIONAL);
        assertThat(EligibilityEvaluator.aggregate(List.of(result(CriterionStatus.PASS), result(CriterionStatus.NOT_APPLICABLE))))
            .isEqualTo(EligibilityStatus.ELIGIBLE);
    }

    @Test void everyRuleResultCarriesItsSourceEvidence() {
        UUID evidenceId = UUID.randomUUID();
        var job = new JobPosting(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "TEST", "测试岗位", JobFamily.SOFTWARE,
            EmploymentType.ESTABLISHMENT, "杭州", 1, EducationLevel.MASTER, Set.of("计算机科学与技术"), Set.of(), 40,
            LocalDate.of(2025, 8, 31), null, Set.of(), "", "https://example.test/official", List.of(evidenceId));
        var result = evaluate(hangzhouCandidate(), job);
        assertThat(result.ruleResults().get(RuleType.EDUCATION).evidenceId()).isEqualTo(evidenceId);
        assertThat(result.ruleResults().get(RuleType.EDUCATION).requirement()).contains("MASTER");
        assertThat(result.ruleResults().get(RuleType.EXACT_MAJOR).candidateFact()).contains("计算机科学与技术");
    }

    private EligibilityAssessment evaluate(CandidateProfile candidate, JobPosting job) { return evaluator.evaluate(candidate, job, NOW); }
    private void assertRule(EligibilityAssessment assessment, RuleType rule, CriterionStatus expected) { assertThat(assessment.ruleResults().get(rule).status()).isEqualTo(expected); }
    private static EligibilityAssessment.RuleResult result(CriterionStatus status) { return new EligibilityAssessment.RuleResult(status, null, null, "test", null); }

    private CandidateProfile hangzhouCandidate() {
        return candidate(PartialDate.month(1992, 12), EducationLevel.MASTER, Set.of("计算机科学与技术"), 2020, 5);
    }

    private CandidateProfile candidate(PartialDate birth, EducationLevel education, Set<String> majors, Integer graduationYear, Integer experience) {
        return new CandidateProfile(UUID.randomUUID(), "test", birth, education, majors, graduationYear, experience, Set.of(), List.of("杭州"), Set.of(EmploymentType.ESTABLISHMENT), "test-v1");
    }

    private JobPosting job(Integer maxAge, LocalDate referenceDate, EducationLevel education, Set<String> majors, Set<Integer> graduationYears, Integer experience) {
        return new JobPosting(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "TEST", "测试岗位", JobFamily.SOFTWARE, EmploymentType.ESTABLISHMENT, "杭州", 1, education, majors, graduationYears, maxAge, referenceDate, experience, Set.of(), "", "https://example.test/official", List.of());
    }
}
