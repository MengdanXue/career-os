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

    @Test void missingMaximumAgeCannotEstablishUnrestrictedEligibility() {
        var candidate = candidate(PartialDate.month(1992, 12), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), 2027, 0);
        var result = evaluate(candidate, job(null, null, EducationLevel.MASTER,
            Set.of("计算机科学与技术"), Set.of(2027), 0));

        assertRule(result, RuleType.AGE, EligibilityStatus.NEEDS_CONFIRMATION);
        assertThat(result.status()).isEqualTo(EligibilityStatus.NEEDS_CONFIRMATION);
    }

    @Test void missingExperienceRequirementCannotEstablishUnrestrictedEligibility() {
        var candidate = candidate(PartialDate.month(1992, 12), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), 2027, 0);
        var result = evaluate(candidate, job(40, LocalDate.of(2027, 1, 1), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), Set.of(2027), null));

        assertRule(result, RuleType.EXPERIENCE, EligibilityStatus.NEEDS_CONFIRMATION);
        assertThat(result.status()).isEqualTo(EligibilityStatus.NEEDS_CONFIRMATION);
    }

    @Test void explicitlyZeroExperienceRequirementAllowsCandidateWithoutWorkHistory() {
        var candidate = candidate(PartialDate.month(1992, 12), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), 2027, null);
        var result = evaluate(candidate, job(40, LocalDate.of(2027, 1, 1), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), Set.of(2027), 0));

        assertRule(result, RuleType.EXPERIENCE, EligibilityStatus.ELIGIBLE);
        assertThat(result.status()).isEqualTo(EligibilityStatus.ELIGIBLE);
    }

    @Test void ageIsEligibleBeforeAllPossibleBirthdays() {
        var result = evaluator.evaluate(candidate(PartialDate.month(1992, 12), EducationLevel.MASTER, Set.of("计算机科学与技术"), 2020, 5), job(32, LocalDate.of(2025, 8, 31), EducationLevel.MASTER, Set.of(), Set.of(), null));
        assertRule(result, RuleType.AGE, EligibilityStatus.ELIGIBLE);
    }

    @Test void partialBirthMonthIsUncertainInsideAgeBoundary() {
        var result = evaluator.evaluate(candidate(PartialDate.month(1992, 12), EducationLevel.MASTER, Set.of("计算机科学与技术"), 2020, 5), job(32, LocalDate.of(2025, 9, 1), EducationLevel.MASTER, Set.of(), Set.of(), null));
        assertRule(result, RuleType.AGE, EligibilityStatus.NEEDS_CONFIRMATION);
        assertThat(result.status()).isEqualTo(EligibilityStatus.NEEDS_CONFIRMATION);
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
        assertRule(evaluator.evaluate(candidate(PartialDate.month(1995, 1), EducationLevel.MASTER, Set.of(), null, 0), restricted), RuleType.GRADUATE_YEAR, EligibilityStatus.NEEDS_CONFIRMATION);
    }

    @Test void legacyExperienceTotalNeverSatisfiesAHardRequirement() {
        var requiresTwo = job(null, null, EducationLevel.MASTER, Set.of(), Set.of(), 2);
        var candidate = candidate(PartialDate.month(1995, 1), EducationLevel.MASTER, Set.of(), 2020, 7);
        assertRule(evaluator.evaluate(candidate, CandidateFacts.resolve(candidate, List.of()), requiresTwo,
            "legacy", Instant.parse("2026-08-23T00:00:00Z")), RuleType.EXPERIENCE, EligibilityStatus.NEEDS_CONFIRMATION);
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

    @Test void missingOfficialQualificationCutoffKeepsVerifiedExperienceUncertain() {
        var requiresTwo = job(null, null, EducationLevel.MASTER, Set.of(), Set.of(), 2);
        var candidate = candidateWithEmployment(List.of(new CandidateEmploymentRecord(
            "杭州测试单位", "Java 工程师", LocalDate.of(2020, 1, 1), LocalDate.of(2025, 12, 31),
            CandidateEmploymentRecord.EmploymentMode.FULL_TIME,
            CandidateEmploymentRecord.VerificationStatus.VERIFIED, Set.of("劳动合同"))));

        var result = evaluator.evaluate(candidate, CandidateFacts.confirmed(candidate), requiresTwo,
            "verified", null, Instant.parse("2026-08-23T12:34:56Z"));

        assertRule(result, RuleType.EXPERIENCE, EligibilityStatus.NEEDS_CONFIRMATION);
        assertThat(result.assessedAt()).isEqualTo(Instant.parse("2026-08-23T12:34:56Z"));
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

        assertRule(result, RuleType.AGE, EligibilityStatus.NEEDS_CONFIRMATION);
        assertThat(result.ruleResults().get(RuleType.AGE).explanation()).contains("未确认");
    }

    // --- CONDITIONAL：结论只取决于一件尚未完成的事 ---

    /**
     * 产品需求 §6.1 的 CONDITIONAL 原型：以境外硕士身份报考，留服认证尚未完成。
     * 这既不是“可报”（认证还没下来），也不是“待确认”（缺什么、什么时候能补上都很清楚）。
     */
    @Test void aMastersDegreeAwaitingOverseasCredentialVerificationIsConditionalNotEligible() {
        var candidate = candidateWithEducation(new EducationRecord("示例海外大学", "示例国",
            EducationLevel.MASTER, "计算机科学与技术", 2027, 6,
            EducationRecord.CompletionStatus.COMPLETED,
            EducationRecord.CredentialVerificationStatus.IN_PROGRESS));
        var result = evaluate(candidate, job(40, LocalDate.of(2027, 1, 1), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), Set.of(2027), 0));

        assertRule(result, RuleType.EDUCATION, EligibilityStatus.CONDITIONAL);
        assertThat(result.ruleResults().get(RuleType.EDUCATION).explanation()).contains("认证");
        assertThat(result.status()).isEqualTo(EligibilityStatus.CONDITIONAL);
    }

    /** 学位本身还没拿到，同样是条件式结论，并且要说清楚在等哪个时间点。 */
    @Test void anExpectedGraduationIsConditionalAndNamesTheDate() {
        var candidate = candidateWithEducation(new EducationRecord("示例海外大学", "示例国",
            EducationLevel.MASTER, "计算机科学与技术", 2027, 6,
            EducationRecord.CompletionStatus.EXPECTED,
            EducationRecord.CredentialVerificationStatus.PLANNED));
        var result = evaluate(candidate, job(40, LocalDate.of(2027, 1, 1), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), Set.of(2027), 0));

        assertRule(result, RuleType.EDUCATION, EligibilityStatus.CONDITIONAL);
        assertThat(result.ruleResults().get(RuleType.EDUCATION).explanation()).contains("2027 年 6 月");
    }

    /** 另有一段已毕业且无需认证的学历能单独满足要求时，就不存在这个条件。 */
    @Test void aSettledDegreeThatAlreadyMeetsTheBarRemovesTheCondition() {
        var candidate = candidateWithEducation(
            new EducationRecord("示例海外大学", "示例国", EducationLevel.MASTER, "计算机科学与技术",
                2027, 6, EducationRecord.CompletionStatus.EXPECTED,
                EducationRecord.CredentialVerificationStatus.PLANNED),
            new EducationRecord("浙江大学", "中国", EducationLevel.MASTER, "计算机科学与技术",
                2018, 6, EducationRecord.CompletionStatus.COMPLETED,
                EducationRecord.CredentialVerificationStatus.NOT_REQUIRED));
        var result = evaluate(candidate, job(40, LocalDate.of(2027, 1, 1), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), Set.of(2027), 0));

        assertRule(result, RuleType.EDUCATION, EligibilityStatus.ELIGIBLE);
    }

    /** 境外学历是否需要认证都还没确认时，只能是待确认——不能替用户假设它不需要认证。 */
    @Test void anUnknownVerificationRequirementNeedsConfirmationRatherThanAssumingNone() {
        var candidate = candidateWithEducation(new EducationRecord("某海外院校", "其他",
            EducationLevel.MASTER, "计算机科学与技术", 2026, 6,
            EducationRecord.CompletionStatus.COMPLETED,
            EducationRecord.CredentialVerificationStatus.UNKNOWN));
        var result = evaluate(candidate, job(40, LocalDate.of(2027, 1, 1), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), Set.of(2027), 0));

        assertRule(result, RuleType.EDUCATION, EligibilityStatus.NEEDS_CONFIRMATION);
    }

    /** 旧资料只有一个汇总学历字段，没填逐段明细不该把已经满足的学历降级成条件式结论。 */
    @Test void aLegacyProfileWithoutEducationRecordsStaysEligible() {
        var candidate = candidate(PartialDate.month(1992, 12), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), 2027, 0);
        var result = evaluate(candidate, job(40, LocalDate.of(2027, 1, 1), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), Set.of(2027), 0));

        assertRule(result, RuleType.EDUCATION, EligibilityStatus.ELIGIBLE);
        assertThat(result.status()).isEqualTo(EligibilityStatus.ELIGIBLE);
    }

    // --- CONFLICTING_EVIDENCE：岗位要求本身不可信 ---

    /**
     * 官方来源在学历要求上互相矛盾时，这条规则不产出判定。说“满足”或“不满足”
     * 都是在替官方决定要求到底是什么。
     */
    @Test void aRuleWhoseOfficialFieldConflictsProducesNoVerdict() {
        var candidate = candidate(PartialDate.month(1992, 12), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), 2027, 0);
        var result = evaluator.evaluate(candidate, CandidateFacts.confirmed(candidate),
            job(40, LocalDate.of(2027, 1, 1), EducationLevel.MASTER, Set.of("计算机科学与技术"), Set.of(2027), 0),
            "verified", LocalDate.of(2027, 1, 1), Instant.parse("2026-08-14T00:00:00Z"),
            EligibilityEvaluator.VERSION, Set.of("educationRequirementText"));

        assertRule(result, RuleType.EDUCATION, EligibilityStatus.CONFLICTING_EVIDENCE);
        assertThat(result.status()).isEqualTo(EligibilityStatus.CONFLICTING_EVIDENCE);
        // 不受冲突影响的规则照常判定。
        assertRule(result, RuleType.AGE, EligibilityStatus.ELIGIBLE);
    }

    /** 一项硬条件明确不满足时，其余条目再不确定也改变不了结果。 */
    @Test void aDefiniteFailureOutranksEveryOtherUnsettledRule() {
        var candidate = candidate(PartialDate.month(1992, 12), EducationLevel.BACHELOR,
            Set.of("计算机科学与技术"), 2027, 0);
        var result = evaluator.evaluate(candidate, CandidateFacts.confirmed(candidate),
            job(40, LocalDate.of(2027, 1, 1), EducationLevel.MASTER, Set.of("计算机科学与技术"), Set.of(2027), 0),
            "verified", LocalDate.of(2027, 1, 1), Instant.parse("2026-08-14T00:00:00Z"),
            EligibilityEvaluator.VERSION, Set.of("ageRequirementText"));

        assertRule(result, RuleType.EDUCATION, EligibilityStatus.INELIGIBLE);
        assertRule(result, RuleType.AGE, EligibilityStatus.CONFLICTING_EVIDENCE);
        assertThat(result.status()).isEqualTo(EligibilityStatus.INELIGIBLE);
    }

    // --- 政治面貌：只认写死的硬性要求 ---

    @Test void anExplicitPartyMembershipRequirementBlocksANonMember() {
        var result = evaluate(candidateWith(DomainEnums.PoliticalAffiliation.NON_MEMBER, Gender.FEMALE),
            jobRequiring("政治面貌为中共党员", null));

        assertRule(result, RuleType.POLITICAL_AFFILIATION, EligibilityStatus.INELIGIBLE);
        assertThat(result.status()).isEqualTo(EligibilityStatus.INELIGIBLE);
    }

    /** "党员优先"是偏好不是门槛。把偏好当门槛会凭空滤掉可报的岗位。 */
    @Test void aPartyMembershipPreferenceIsNotAHardRequirement() {
        var result = evaluate(candidateWith(DomainEnums.PoliticalAffiliation.NON_MEMBER, Gender.FEMALE),
            jobRequiring("中共党员优先", null));

        assertRule(result, RuleType.POLITICAL_AFFILIATION, EligibilityStatus.ELIGIBLE);
    }

    /** 多数公告写"中共党员（含预备党员）"，也有只要正式党员的。公告没说清楚就不替它决定。 */
    @Test void aProbationaryMemberNeedsTheNoticeToSayWhetherItCounts() {
        var result = evaluate(candidateWith(DomainEnums.PoliticalAffiliation.CPC_PROBATIONARY, Gender.FEMALE),
            jobRequiring("政治面貌为中共党员", null));

        assertRule(result, RuleType.POLITICAL_AFFILIATION, EligibilityStatus.NEEDS_CONFIRMATION);
    }

    @Test void aPartyMemberSatisfiesAnExplicitRequirement() {
        var result = evaluate(candidateWith(DomainEnums.PoliticalAffiliation.CPC_MEMBER, Gender.FEMALE),
            jobRequiring("政治面貌为中共党员", null));

        assertRule(result, RuleType.POLITICAL_AFFILIATION, EligibilityStatus.ELIGIBLE);
    }

    @Test void anUnconfirmedAffiliationCannotSatisfyAnExplicitRequirement() {
        var candidate = candidateWith(DomainEnums.PoliticalAffiliation.CPC_MEMBER, Gender.FEMALE);
        var result = evaluator.evaluate(candidate,
            CandidateFacts.resolve(candidate, List.of()),
            jobRequiring("政治面貌为中共党员", null),
            "verified", Instant.parse("2026-08-14T00:00:00Z"));

        assertRule(result, RuleType.POLITICAL_AFFILIATION, EligibilityStatus.NEEDS_CONFIRMATION);
    }

    // --- 性别：读公告已经写明的限定 ---

    @Test void aPostRestrictedToMenBlocksAFemaleCandidate() {
        var result = evaluate(candidateWith(DomainEnums.PoliticalAffiliation.NON_MEMBER, Gender.FEMALE),
            jobRequiring(null, "男"));

        assertRule(result, RuleType.GENDER, EligibilityStatus.INELIGIBLE);
    }

    @Test void anUnrestrictedGenderFieldIsNotACondition() {
        var result = evaluate(candidateWith(DomainEnums.PoliticalAffiliation.NON_MEMBER, Gender.FEMALE),
            jobRequiring(null, "不限"));

        assertRule(result, RuleType.GENDER, EligibilityStatus.ELIGIBLE);
    }

    @Test void aMatchingGenderRestrictionIsSatisfied() {
        var result = evaluate(candidateWith(DomainEnums.PoliticalAffiliation.NON_MEMBER, Gender.FEMALE),
            jobRequiring(null, "女"));

        assertRule(result, RuleType.GENDER, EligibilityStatus.ELIGIBLE);
    }

    /** 写了限定但两性都提到（或都没提到）时无法解析，不猜。 */
    @Test void anUnparseableGenderRestrictionNeedsConfirmation() {
        var result = evaluate(candidateWith(DomainEnums.PoliticalAffiliation.NON_MEMBER, Gender.FEMALE),
            jobRequiring(null, "男女各一名"));

        assertRule(result, RuleType.GENDER, EligibilityStatus.NEEDS_CONFIRMATION);
    }

    private CandidateProfile candidateWith(DomainEnums.PoliticalAffiliation affiliation, Gender gender) {
        return new CandidateProfile(UUID.randomUUID(), "test", PartialDate.month(1992, 12),
            EducationLevel.MASTER, Set.of("计算机科学与技术"), 2027, 0, Set.of(), List.of("杭州"),
            Set.of(EmploymentType.ESTABLISHMENT), "test-v1", Set.of(), Set.of(), Set.of(), Set.of(),
            List.of(), gender, affiliation, List.of());
    }

    private JobPosting jobRequiring(String otherRequirements, String genderRequirement) {
        return new JobPosting(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "TEST", "测试岗位",
            JobFamily.SOFTWARE, EmploymentType.ESTABLISHMENT, "杭州", 1, EducationLevel.MASTER,
            Set.of("计算机科学与技术"), Set.of(2027), 40, LocalDate.of(2027, 1, 1), 0, Set.of(), "",
            "https://example.test/official", List.of(),
            null, null, null, null, null, null, null, genderRequirement, null,
            otherRequirements, null, null, null, null);
    }

    private CandidateProfile candidateWithEducation(EducationRecord... records) {
        return new CandidateProfile(UUID.randomUUID(), "test", PartialDate.month(1992, 12),
            EducationLevel.MASTER, Set.of("计算机科学与技术"), 2027, 0, Set.of(), List.of("杭州"),
            Set.of(EmploymentType.ESTABLISHMENT), "test-v1", Set.of(), Set.of(), Set.of(), Set.of(),
            List.of(records));
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
