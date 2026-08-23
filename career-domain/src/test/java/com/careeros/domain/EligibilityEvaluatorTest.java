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

    @Test void ageIsEligibleBeforeAllPossibleBirthdays() {
        var result = evaluator.evaluate(candidate(PartialDate.month(1992, 12), EducationLevel.MASTER, Set.of("计算机科学与技术"), 2020, 5), job(32, LocalDate.of(2025, 8, 31), EducationLevel.MASTER, Set.of(), Set.of(), null));
        assertRule(result, RuleType.AGE, EligibilityStatus.ELIGIBLE);
    }

    @Test void partialBirthMonthIsUncertainInsideAgeBoundary() {
        var result = evaluator.evaluate(candidate(PartialDate.month(1992, 12), EducationLevel.MASTER, Set.of("计算机科学与技术"), 2020, 5), job(32, LocalDate.of(2025, 9, 1), EducationLevel.MASTER, Set.of(), Set.of(), null));
        assertRule(result, RuleType.AGE, EligibilityStatus.UNCERTAIN);
        assertThat(result.status()).isEqualTo(EligibilityStatus.UNCERTAIN);
    }

    @Test void ageIsIneligibleAfterAllPossibleBirthdays() {
        var result = evaluator.evaluate(candidate(PartialDate.month(1992, 12), EducationLevel.MASTER, Set.of("计算机科学与技术"), 2020, 5), job(32, LocalDate.of(2025, 10, 1), EducationLevel.MASTER, Set.of(), Set.of(), null));
        assertRule(result, RuleType.AGE, EligibilityStatus.INELIGIBLE);
    }

    @Test void educationUsesOrderedMinimumLevel() {
        var pass = evaluator.evaluate(candidate(PartialDate.exact(LocalDate.of(1995, 1, 1)), EducationLevel.MASTER, Set.of(), 2020, 1), job(null, null, EducationLevel.BACHELOR, Set.of(), Set.of(), null));
        var fail = evaluator.evaluate(candidate(PartialDate.exact(LocalDate.of(1995, 1, 1)), EducationLevel.BACHELOR, Set.of(), 2020, 1), job(null, null, EducationLevel.MASTER, Set.of(), Set.of(), null));
        assertRule(pass, RuleType.EDUCATION, EligibilityStatus.ELIGIBLE);
        assertRule(fail, RuleType.EDUCATION, EligibilityStatus.INELIGIBLE);
    }

    @Test void exactMajorDoesNotExpandBroadSynonyms() {
        var candidate = candidate(PartialDate.exact(LocalDate.of(1995, 1, 1)), EducationLevel.MASTER, Set.of("计算机科学与技术"), 2020, 5);
        var pass = evaluator.evaluate(candidate, job(null, null, EducationLevel.MASTER, Set.of("计算机科学与技术"), Set.of(), null));
        var fail = evaluator.evaluate(candidate, job(null, null, EducationLevel.MASTER, Set.of("软件工程"), Set.of(), null));
        assertRule(pass, RuleType.EXACT_MAJOR, EligibilityStatus.ELIGIBLE);
        assertRule(fail, RuleType.EXACT_MAJOR, EligibilityStatus.INELIGIBLE);
    }

    @Test void explicitMajorInsideAnOfficialRestrictedCategoryIsEligible() {
        var candidate = candidate(PartialDate.exact(LocalDate.of(1995, 1, 1)), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), 2020, 5);
        var restricted = job(null, null, EducationLevel.MASTER,
            Set.of("计算机科学与技术类（限计算机科学与技术"), Set.of(), null);

        assertRule(evaluator.evaluate(candidate, restricted), RuleType.EXACT_MAJOR, EligibilityStatus.ELIGIBLE);
    }

    @Test void graduateRestrictionIsDeterministicAndMissingDataIsUncertain() {
        var restricted = job(null, null, EducationLevel.MASTER, Set.of(), Set.of(2025, 2026), null);
        assertRule(evaluator.evaluate(candidate(PartialDate.month(1995, 1), EducationLevel.MASTER, Set.of(), 2026, 0), restricted), RuleType.GRADUATE_YEAR, EligibilityStatus.ELIGIBLE);
        assertRule(evaluator.evaluate(candidate(PartialDate.month(1995, 1), EducationLevel.MASTER, Set.of(), 2024, 0), restricted), RuleType.GRADUATE_YEAR, EligibilityStatus.INELIGIBLE);
        assertRule(evaluator.evaluate(candidate(PartialDate.month(1995, 1), EducationLevel.MASTER, Set.of(), null, 0), restricted), RuleType.GRADUATE_YEAR, EligibilityStatus.UNCERTAIN);
    }

    @Test void legacyExperienceTotalNeverSatisfiesAHardRequirement() {
        var requiresTwo = job(null, null, EducationLevel.MASTER, Set.of(), Set.of(), 2);
        var candidate = candidate(PartialDate.month(1995, 1), EducationLevel.MASTER, Set.of(), 2020, 7);
        assertRule(evaluator.evaluate(candidate, CandidateFacts.resolve(candidate, List.of()), requiresTwo,
            "legacy", Instant.parse("2026-08-23T00:00:00Z")), RuleType.EXPERIENCE, EligibilityStatus.UNCERTAIN);
    }

    @Test void verifiedFullTimeIntervalsDetermineTheExperienceBoundary() {
        var requiresTwo = job(null, null, EducationLevel.MASTER, Set.of(), Set.of(), 2);
        var candidate = candidateWithEmployment(List.of(new CandidateEmploymentRecord(
            "杭州测试单位", "Java 工程师", LocalDate.of(2022, 1, 1), LocalDate.of(2023, 12, 31),
            CandidateEmploymentRecord.EmploymentMode.FULL_TIME,
            CandidateEmploymentRecord.VerificationStatus.VERIFIED, Set.of("劳动合同"))));
        var facts = CandidateFacts.confirmed(candidate);

        assertRule(evaluator.evaluate(candidate, facts, requiresTwo, "verified", Instant.parse("2026-08-23T00:00:00Z")), RuleType.EXPERIENCE, EligibilityStatus.ELIGIBLE);
    }

    @Test void specificCandidateTitleSatisfiesAGenericRequiredLevel() {
        var candidate = new CandidateProfile(UUID.randomUUID(), "test", PartialDate.exact(LocalDate.of(1995, 1, 1)),
            EducationLevel.MASTER, Set.of("计算机科学与技术"), 2020, 5,
            Set.of("中级：计算机应用（评审）"), List.of("杭州"),
            Set.of(EmploymentType.ESTABLISHMENT), "test-v1");
        var job = new JobPosting(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "TEST", "信息管理",
            JobFamily.INFORMATION_SYSTEMS, EmploymentType.UNKNOWN, "杭州", 1, EducationLevel.MASTER,
            Set.of("计算机科学与技术"), Set.of(), null, null, null, Set.of("中级"), "",
            "https://example.test/official", List.of());

        assertRule(evaluator.evaluate(candidate, job), RuleType.PROFESSIONAL_TITLE, EligibilityStatus.ELIGIBLE);
    }

    @Test void unconfirmedCandidateFactCannotSatisfyAnAgeRule() {
        var candidate = candidate(PartialDate.exact(LocalDate.of(1995, 1, 1)), EducationLevel.MASTER, Set.of(), 2020, 2);
        var result = evaluator.evaluate(candidate, CandidateFacts.resolve(candidate, List.of()),
            job(40, LocalDate.of(2026, 1, 1), EducationLevel.MASTER, Set.of(), Set.of(), null));

        assertRule(result, RuleType.AGE, EligibilityStatus.UNCERTAIN);
        assertThat(result.ruleResults().get(RuleType.AGE).explanation()).contains("未确认");
    }

    private EligibilityAssessment evaluate(CandidateProfile candidate, JobPosting job) { return evaluator.evaluate(candidate, job, Instant.parse("2026-08-14T00:00:00Z")); }
    private void assertRule(EligibilityAssessment assessment, RuleType rule, EligibilityStatus expected) { assertThat(assessment.ruleResults().get(rule).status()).isEqualTo(expected); }

    private CandidateProfile candidate(PartialDate birth, EducationLevel education, Set<String> majors, Integer graduationYear, Integer experience) {
        return new CandidateProfile(UUID.randomUUID(), "test", birth, education, majors, graduationYear, experience, Set.of(), List.of("杭州"), Set.of(EmploymentType.ESTABLISHMENT), "test-v1");
    }

    private CandidateProfile candidateWithEmployment(List<CandidateEmploymentRecord> employment) {
        return new CandidateProfile(UUID.randomUUID(), "test", PartialDate.month(1995, 1),
            EducationLevel.MASTER, Set.of("计算机科学与技术"), 2020, 7, Set.of(), List.of("杭州"),
            Set.of(EmploymentType.ESTABLISHMENT), "test-v1", Set.of(), Set.of(), Set.of(), Set.of(),
            List.of(), DomainEnums.Gender.FEMALE, DomainEnums.PoliticalAffiliation.NON_MEMBER, employment);
    }

    private JobPosting job(Integer maxAge, LocalDate referenceDate, EducationLevel education, Set<String> majors, Set<Integer> graduationYears, Integer experience) {
        return new JobPosting(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "TEST", "测试岗位", JobFamily.SOFTWARE, EmploymentType.ESTABLISHMENT, "杭州", 1, education, majors, graduationYears, maxAge, referenceDate, experience, Set.of(), "", "https://example.test/official", List.of());
    }
}
