package com.careeros.domain;

import com.careeros.domain.DomainEnums.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    // --- 应届身份：报名时状态是声明，不是既成事实 ---

    /**
     * 公告没采集到 / 没解析出来时不能说"通过"——读不到不等于没限制。
     * 这是本轮修掉的错判：此前 rule==null 一律判 ELIGIBLE。
     */
    @Test void anUnparsedGraduateClauseCannotBeReadAsUnrestricted() {
        var result = evaluateWithGraduateRule(declaring(ApplicationTimeStatus.UNDECLARED), null);
        assertRule(result, RuleType.FRESH_GRADUATE_STATUS, EligibilityStatus.NEEDS_CONFIRMATION);
        assertThat(result.ruleResults().get(RuleType.FRESH_GRADUATE_STATUS).explanation())
            .contains("尚未解析");
    }

    /** 公告处理过、确实没有应届条款，这是可以下结论的事实。 */
    @Test void aProcessedNoticeWithNoGraduateClauseDoesNotRestrict() {
        var result = evaluateWithGraduateRule(declaring(ApplicationTimeStatus.UNDECLARED),
            processedWithoutClause());
        assertRule(result, RuleType.FRESH_GRADUATE_STATUS, EligibilityStatus.ELIGIBLE);
    }

    /** 有应届条款但不限定工作单位或社保时，同样不构成限制。 */
    @Test void aGraduateClauseThatRestrictsNeitherIsNotACondition() {
        var result = evaluateWithGraduateRule(declaring(ApplicationTimeStatus.UNDECLARED),
            graduateRule(false, false, GraduateEligibilityRule.EvidenceState.CONFIRMED));
        assertRule(result, RuleType.FRESH_GRADUATE_STATUS, EligibilityStatus.ELIGIBLE);
    }

    /**
     * 声明"报名时将未落实工作单位"只能得出条件式结论。报名还没发生，把一个还没兑现的
     * 未来当成既成事实，正是基线禁止的。
     */
    @Test void declaringThatTheRequirementWillBeMetIsConditionalNotEligible() {
        var result = evaluateWithGraduateRule(declaring(ApplicationTimeStatus.DECLARED_MET),
            graduateRule(true, false, GraduateEligibilityRule.EvidenceState.CONFIRMED));

        assertRule(result, RuleType.FRESH_GRADUATE_STATUS, EligibilityStatus.CONDITIONAL);
        assertThat(result.ruleResults().get(RuleType.FRESH_GRADUATE_STATUS).explanation())
            .contains("报名时仍然");
        assertThat(result.status()).isEqualTo(EligibilityStatus.CONDITIONAL);
    }

    /** 声明届时不满足是一个已经确定的事实，可以明确判不可报。 */
    @Test void declaringThatTheRequirementWillNotBeMetIsIneligible() {
        var result = evaluateWithGraduateRule(declaring(ApplicationTimeStatus.DECLARED_NOT_MET),
            graduateRule(true, false, GraduateEligibilityRule.EvidenceState.CONFIRMED));

        assertRule(result, RuleType.FRESH_GRADUATE_STATUS, EligibilityStatus.INELIGIBLE);
        assertThat(result.status()).isEqualTo(EligibilityStatus.INELIGIBLE);
    }

    @Test void anUndeclaredApplicationTimeStatusNeedsConfirmation() {
        var result = evaluateWithGraduateRule(declaring(ApplicationTimeStatus.UNDECLARED),
            graduateRule(true, false, GraduateEligibilityRule.EvidenceState.CONFIRMED));
        assertRule(result, RuleType.FRESH_GRADUATE_STATUS, EligibilityStatus.NEEDS_CONFIRMATION);
    }

    /** 条款本身还没核实时不拿它判定，先让人核对公告原文。 */
    @Test void anUnconfirmedGraduateClauseIsNotUsedToDecide() {
        var result = evaluateWithGraduateRule(declaring(ApplicationTimeStatus.DECLARED_NOT_MET),
            graduateRule(true, false, GraduateEligibilityRule.EvidenceState.REVIEW_REQUIRED));
        assertRule(result, RuleType.FRESH_GRADUATE_STATUS, EligibilityStatus.NEEDS_CONFIRMATION);
    }

    /** 社保限定与工作单位限定各自独立，任一不满足即整条不满足。 */
    @Test void theSocialInsuranceRestrictionIsCheckedIndependently() {
        var candidate = declaring(ApplicationTimeStatus.DECLARED_MET, ApplicationTimeStatus.DECLARED_NOT_MET);
        var result = evaluateWithGraduateRule(candidate,
            graduateRule(true, true, GraduateEligibilityRule.EvidenceState.CONFIRMED));

        assertRule(result, RuleType.FRESH_GRADUATE_STATUS, EligibilityStatus.INELIGIBLE);
        assertThat(result.ruleResults().get(RuleType.FRESH_GRADUATE_STATUS).explanation())
            .contains("社保");
    }

    // --- §10.6：逐条结论挂到具体证据片段 ---

    /** 每条规则挂自己那个官方字段的片段，而不是统一挂公告级证据。 */
    @Test void eachRuleCitesTheFragmentsOfItsOwnOfficialField() {
        var ageFragment = UUID.randomUUID();
        var educationFragment = UUID.randomUUID();
        var candidate = candidate(PartialDate.month(1992, 12), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), 2027, 0);

        var result = evaluateCiting(candidate, Map.of(
            "ageRequirementText", List.of(ageFragment),
            "educationRequirementText", List.of(educationFragment)));

        assertThat(result.ruleResults().get(RuleType.AGE).evidenceIds()).containsExactly(ageFragment);
        assertThat(result.ruleResults().get(RuleType.EDUCATION).evidenceIds())
            .containsExactly(educationFragment);
    }

    /** 一条事实本就可能引用多个片段，不能只留第一个。 */
    @Test void aFieldWithSeveralFragmentsKeepsAllOfThem() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var candidate = candidate(PartialDate.month(1992, 12), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), 2027, 0);

        var result = evaluateCiting(candidate, Map.of("ageRequirementText", List.of(first, second)));

        assertThat(result.ruleResults().get(RuleType.AGE).evidenceIds()).containsExactly(first, second);
    }

    /** 没有片段级证据时保持为空，读者回落到公告级，而不是伪造一个片段。 */
    @Test void aFieldWithoutFragmentsCitesNothingRatherThanInventingOne() {
        var candidate = candidate(PartialDate.month(1992, 12), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), 2027, 0);

        var result = evaluateCiting(candidate, Map.of());

        assertThat(result.ruleResults().get(RuleType.AGE).evidenceIds()).isEmpty();
        // 公告级证据仍在评估上，读者据此回落——逐条为空不等于整条结论无据可查。
        assertThat(result.evidenceIds()).isNotNull();
    }

    private EligibilityAssessment evaluateCiting(
        CandidateProfile candidate, Map<String, List<UUID>> fieldEvidence
    ) {
        return evaluator.evaluate(candidate, CandidateFacts.confirmed(candidate),
            job(40, LocalDate.of(2027, 1, 1), EducationLevel.MASTER, Set.of("计算机科学与技术"), Set.of(2027), 0),
            "verified", LocalDate.of(2027, 1, 1), Instant.parse("2026-08-14T00:00:00Z"),
            EligibilityEvaluator.VERSION, Set.of(), null, fieldEvidence);
    }

    private EligibilityAssessment evaluateWithGraduateRule(
        CandidateProfile candidate, GraduateEligibilityRule rule
    ) {
        return evaluator.evaluate(candidate, CandidateFacts.confirmed(candidate),
            job(40, LocalDate.of(2027, 1, 1), EducationLevel.MASTER, Set.of("计算机科学与技术"), Set.of(2027), 0),
            "verified", LocalDate.of(2027, 1, 1), Instant.parse("2026-08-14T00:00:00Z"),
            EligibilityEvaluator.VERSION, Set.of(), rule);
    }

    private static GraduateEligibilityRule graduateRule(
        boolean requiresNoEmployer, boolean restrictsSocialInsurance,
        GraduateEligibilityRule.EvidenceState evidenceState
    ) {
        return new GraduateEligibilityRule(2027, Set.of(2027),
            Set.of(GraduateEligibilityRule.CohortScope.CURRENT_YEAR), true,
            GraduateEligibilityRule.RequirementTiming.UNSPECIFIED, null,
            GraduateEligibilityRule.RequirementTiming.UNSPECIFIED, null,
            requiresNoEmployer, restrictsSocialInsurance, "应届条款原文", evidenceState);
    }

    private CandidateProfile declaring(ApplicationTimeStatus employer) {
        return declaring(employer, employer);
    }

    private CandidateProfile declaring(ApplicationTimeStatus employer, ApplicationTimeStatus insurance) {
        return new CandidateProfile(UUID.randomUUID(), "test", PartialDate.month(1992, 12),
            EducationLevel.MASTER, Set.of("计算机科学与技术"), 2027, 0, Set.of(), List.of("杭州"),
            Set.of(EmploymentType.ESTABLISHMENT), "test-v1", Set.of(), Set.of(), Set.of(), Set.of(),
            List.of(), Gender.FEMALE, DomainEnums.PoliticalAffiliation.NON_MEMBER, List.of(),
            employer, insurance);
    }

    // --- 回归：把"读不到"或"偏好"当成通过/门槛 ---
    // 这五条是同一类错判：结构化字段为空时分不清"公告没限制"和"没解析出来"。
    // AGE 与 EXPERIENCE 一开始就按 null 落待确认；其余规则曾经一律判通过。

    /** "男性优先"是招录倾向，不是报名门槛。当成门槛会凭空滤掉可报的岗位。 */
    @Test void aGenderPreferenceIsNotAHardGate() {
        var result = evaluate(candidateWith(DomainEnums.PoliticalAffiliation.NON_MEMBER, Gender.FEMALE),
            jobRequiring(null, "男性优先"));
        assertRule(result, RuleType.GENDER, EligibilityStatus.ELIGIBLE);
    }

    /** "男女各一名"两性都提到，判断不了限哪一边，不能当成没限制。 */
    @Test void anAmbiguousGenderClauseIsNotReadAsUnrestricted() {
        var result = evaluate(candidateWith(DomainEnums.PoliticalAffiliation.NON_MEMBER, Gender.FEMALE),
            jobRequiring(null, "男女各一名"));
        assertRule(result, RuleType.GENDER, EligibilityStatus.NEEDS_CONFIRMATION);
    }

    /** 真正写死的限定仍要判不可报，修偏好误判不能把门槛也一起放过。 */
    @Test void anExplicitGenderRestrictionStillBlocks() {
        var result = evaluate(candidateWith(DomainEnums.PoliticalAffiliation.NON_MEMBER, Gender.FEMALE),
            jobRequiring(null, "限男性"));
        assertRule(result, RuleType.GENDER, EligibilityStatus.INELIGIBLE);
    }

    /** 专业栏有原文但没解析出结构化目录 = 解析失败，不是"不限专业"。 */
    @Test void anUnparsedMajorClauseIsNotReadAsUnrestricted() {
        var candidate = candidate(PartialDate.month(1992, 12), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), 2027, 0);
        var result = evaluate(candidate, jobWithRawText("majorRequirementText", "计算机类相关专业，详见附件"));
        assertRule(result, RuleType.EXACT_MAJOR, EligibilityStatus.NEEDS_CONFIRMATION);
    }

    /** 招聘对象栏有原文但没解析出届别 = 解析失败，不是"无届别限制"。 */
    @Test void anUnparsedGraduationCohortIsNotReadAsUnrestricted() {
        var candidate = candidate(PartialDate.month(1992, 12), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), 2027, 0);
        var result = evaluate(candidate, jobWithRawText("candidateScope", "2027届毕业生及社会人员"));
        assertRule(result, RuleType.GRADUATE_YEAR, EligibilityStatus.NEEDS_CONFIRMATION);
    }

    /** 其他条件栏有原文但没解析出职称要求 = 解析失败，不是"无职称要求"。 */
    @Test void anUnparsedProfessionalTitleClauseIsNotReadAsUnrestricted() {
        var candidate = candidate(PartialDate.month(1992, 12), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), 2027, 0);
        var result = evaluate(candidate, jobWithRawText("otherRequirements", "须具备中级及以上职称"));
        assertRule(result, RuleType.PROFESSIONAL_TITLE, EligibilityStatus.NEEDS_CONFIRMATION);
    }

    /** 原文栏本身为空时才是真的没限制，修正不能把所有岗位都推成待确认。 */
    @Test void anAbsentRawClauseStillMeansUnrestricted() {
        var candidate = candidate(PartialDate.month(1992, 12), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), 2027, 0);
        var result = evaluate(candidate, jobWithRawText("majorRequirementText", null));
        assertRule(result, RuleType.EXACT_MAJOR, EligibilityStatus.ELIGIBLE);
        assertRule(result, RuleType.GRADUATE_YEAR, EligibilityStatus.ELIGIBLE);
        assertRule(result, RuleType.PROFESSIONAL_TITLE, EligibilityStatus.ELIGIBLE);
    }

    /** 按字段名放一段原文，其余原文栏留空，用来隔离单条规则的解析失败判定。 */
    private JobPosting jobWithRawText(String field, String rawText) {
        return new JobPosting(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "TEST", "测试岗位",
            JobFamily.SOFTWARE, EmploymentType.ESTABLISHMENT, "杭州", 1, EducationLevel.MASTER,
            Set.of(), Set.of(), 40, LocalDate.of(2027, 1, 1), 0, Set.of(), "",
            "https://example.test/official", List.of(),
            null, null, null, null, null,
            "majorRequirementText".equals(field) ? rawText : null,
            null, null,
            "candidateScope".equals(field) ? rawText : null,
            "otherRequirements".equals(field) ? rawText : null,
            null, null, null, null);
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

    /**
     * 默认按"公告已处理且没有应届条款"评估。这些用例测的是别的规则，不显式声明的话
     * 整体结论会被"应届条款未解析"盖过——那正是本轮修掉的错判，不该让它掩盖其它断言。
     */
    private EligibilityAssessment evaluate(CandidateProfile candidate, JobPosting job) {
        return evaluator.evaluate(candidate, CandidateFacts.confirmed(candidate), job, "verified",
            LocalDate.of(2027, 1, 1), Instant.parse("2026-08-14T00:00:00Z"),
            EligibilityEvaluator.VERSION, Set.of(), processedWithoutClause(), Map.of());
    }

    private static GraduateEligibilityRule processedWithoutClause() {
        return new GraduateEligibilityRule(2027, Set.of(), Set.of(), false,
            GraduateEligibilityRule.RequirementTiming.UNSPECIFIED, null,
            GraduateEligibilityRule.RequirementTiming.UNSPECIFIED, null,
            false, false, "", GraduateEligibilityRule.EvidenceState.NOT_REQUIRED);
    }
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
