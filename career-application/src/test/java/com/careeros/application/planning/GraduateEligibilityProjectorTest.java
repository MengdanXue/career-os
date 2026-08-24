package com.careeros.application.planning;

import static com.careeros.application.planning.CareerPlan.QualificationOutcome.CONDITIONALLY_ELIGIBLE;
import static com.careeros.application.planning.CareerPlan.QualificationOutcome.INELIGIBLE;
import static com.careeros.application.planning.GraduateEligibilityProjector.EvaluationMode.HISTORICAL_ACTUAL;
import static com.careeros.application.planning.GraduateEligibilityProjector.EvaluationMode.TARGET_YEAR_ANALOG;
import static com.careeros.application.planning.GraduateEligibilityProjector.GraduateTrack.TARGET_YEAR_GRADUATE;
import static com.careeros.domain.CandidateEmploymentRecord.EmploymentMode.FULL_TIME;
import static com.careeros.domain.CandidateEmploymentRecord.VerificationStatus.VERIFIED;
import static com.careeros.domain.DomainEnums.EducationLevel.BACHELOR;
import static com.careeros.domain.DomainEnums.EducationLevel.MASTER;
import static com.careeros.domain.EducationRecord.CompletionStatus.COMPLETED;
import static com.careeros.domain.EducationRecord.CompletionStatus.EXPECTED;
import static com.careeros.domain.EducationRecord.CredentialVerificationStatus.NOT_REQUIRED;
import static com.careeros.domain.EducationRecord.CredentialVerificationStatus.PLANNED;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.domain.CandidateEmploymentRecord;
import com.careeros.domain.CandidateFacts;
import com.careeros.domain.CandidateProfile;
import com.careeros.domain.EducationRecord;
import com.careeros.domain.GraduateEligibilityRule;
import com.careeros.domain.PartialDate;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GraduateEligibilityProjectorTest {
    private final GraduateEligibilityProjector projector = new GraduateEligibilityProjector();

    @Test
    void keepsHistoricalTruthButTreats2027GraduateAsCurrentCohortIn2027Analog() {
        var candidate = candidateExpectedIn2027(List.of());
        var facts = CandidateFacts.confirmed(candidate);

        var actual = projector.assess(candidate, facts, rule2026(), HISTORICAL_ACTUAL, 2027);
        var analog = projector.assess(candidate, facts, rule2026(), TARGET_YEAR_ANALOG, 2027);

        assertThat(actual.outcome()).isEqualTo(INELIGIBLE);
        assertThat(actual.reasons()).contains("2027 届不属于 2026 年公告的历史实际毕业生范围");
        assertThat(analog.track()).isEqualTo(TARGET_YEAR_GRADUATE);
        assertThat(analog.outcome()).isEqualTo(CONDITIONALLY_ELIGIBLE);
        assertThat(analog.reasons()).contains("2027 届属于目标年度当届毕业生范围");
    }

    @Test
    void priorEmploymentDoesNotEraseCurrentCohortWithoutAnExplicitNoEmploymentRule() {
        var employment = new CandidateEmploymentRecord(
            "某科技公司", "Java 开发工程师", LocalDate.of(2014, 7, 1),
            LocalDate.of(2022, 8, 31), FULL_TIME, VERIFIED, Set.of("离职证明"));
        var candidate = candidateExpectedIn2027(List.of(employment));

        var assessment = projector.assess(candidate, CandidateFacts.confirmed(candidate),
            rule2026(), TARGET_YEAR_ANALOG, 2027);

        assertThat(assessment.outcome()).isNotEqualTo(INELIGIBLE);
        assertThat(assessment.reasons()).contains("公告未明示限制既往工作经历");
    }

    @Test
    void confirmedEmploymentHistoryDoesNotPretendAnIndependentSocialInsuranceRestrictionIsSatisfied() {
        var candidate = candidateExpectedIn2027(List.of());
        var base = rule2026();
        var socialInsuranceRestricted = new GraduateEligibilityRule(
            base.recruitmentYear(), base.explicitGraduationYears(), base.cohorts(),
            base.includesOverseasGraduates(), base.degreeTiming(), base.degreeDeadline(),
            base.credentialTiming(), base.credentialDeadline(), false, true,
            "应届毕业生不得缴纳社会保险", base.evidenceState());

        var assessment = projector.assess(candidate, CandidateFacts.confirmed(candidate),
            socialInsuranceRestricted, TARGET_YEAR_ANALOG, 2027);

        assertThat(assessment.outcome()).isEqualTo(CONDITIONALLY_ELIGIBLE);
        assertThat(assessment.reasons()).contains("社保限制已明示，但尚无独立核验的社保期间事实");
    }

    private static GraduateEligibilityRule rule2026() {
        return GraduateEligibilityRule.fromExplicitYears(
            2026, Set.of(2024, 2025, 2026), true,
            "2024年、2025年和2026年普通高校毕业生，含同期留学回国人员");
    }

    private static CandidateProfile candidateExpectedIn2027(List<CandidateEmploymentRecord> employment) {
        return new CandidateProfile(
            UUID.fromString("01992f09-0000-7000-8000-000000000001"), "测试候选人", new PartialDate(1992, 12, 31),
            BACHELOR, Set.of("计算机科学与技术"), 2014, 7, Set.of("计算机应用中级"),
            List.of("杭州", "浙江"), Set.of(), "profile-test", Set.of(), Set.of(), Set.of(), Set.of(),
            List.of(
                new EducationRecord("某本科院校", "中国", BACHELOR, "计算机科学与技术", 2014, 6,
                    COMPLETED, NOT_REQUIRED),
                new EducationRecord("示例海外大学", "示例国", MASTER, "计算机科学", 2027, 6,
                    EXPECTED, PLANNED)),
            null, null, employment);
    }
}
