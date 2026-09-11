package com.careeros.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.domain.DomainEnums.ApplicationTimeStatus;
import com.careeros.domain.DomainEnums.EducationLevel;
import com.careeros.domain.DomainEnums.EmploymentType;
import com.careeros.domain.DomainEnums.Gender;
import com.careeros.domain.DomainEnums.JobFamily;
import com.careeros.domain.DomainEnums.OrganizationType;
import com.careeros.domain.DomainEnums.PoliticalAffiliation;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * 逐字段重建资料时漏抄一个字段，不会报错，只会安静地把用户填过的东西重置掉——
 * 曾经漏掉两个报名时状态字段，每保存一次资料就清空一次声明。
 *
 * <p>所以这里不写死"应该保留哪些字段"，而是用记录组件逐个比对：将来给 {@link CandidateProfile}
 * 新增字段却忘了带进 {@code copy}，这些用例会失败，不需要有人记得回来补测试。
 */
class CandidateProfileCopyTest {

    private static CandidateProfile populated() {
        return new CandidateProfile(
            UUID.randomUUID(), "薛某", new PartialDate(1997, 4, 12), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), 2026, 2, Set.of("中级工程师"), List.of("杭州", "宁波"),
            Set.of(EmploymentType.ESTABLISHMENT), "profile-fixture", Set.of("Java"), Set.of("知识图谱"),
            Set.of(JobFamily.INFORMATION_SYSTEMS), Set.of(OrganizationType.PUBLIC_INSTITUTION),
            List.of(new EducationRecord("某大学", "英国", EducationLevel.MASTER, "Computer Science", 2026, 9,
                EducationRecord.CompletionStatus.EXPECTED, EducationRecord.CredentialVerificationStatus.IN_PROGRESS)),
            Gender.FEMALE, PoliticalAffiliation.CPC_MEMBER,
            List.of(new CandidateEmploymentRecord("某公司", "工程师", LocalDate.of(2024, 7, 1), null,
                CandidateEmploymentRecord.EmploymentMode.FULL_TIME,
                CandidateEmploymentRecord.VerificationStatus.VERIFIED, Set.of("LABOUR_CONTRACT"))),
            ApplicationTimeStatus.DECLARED_MET, ApplicationTimeStatus.DECLARED_NOT_MET
        );
    }

    @Test void changingGenderKeepsEveryOtherField() {
        assertOnlyChanges("gender", profile -> profile.withGender(Gender.MALE));
    }

    @Test void changingPoliticalAffiliationKeepsEveryOtherField() {
        assertOnlyChanges("politicalAffiliation",
            profile -> profile.withPoliticalAffiliation(PoliticalAffiliation.NON_MEMBER));
    }

    @Test void changingEmployerSettlementKeepsEveryOtherField() {
        assertOnlyChanges("employerSettlementAtApplication",
            profile -> profile.withEmployerSettlementAtApplication(ApplicationTimeStatus.DECLARED_NOT_MET));
    }

    @Test void changingSocialInsuranceKeepsEveryOtherField() {
        assertOnlyChanges("socialInsuranceAtApplication",
            profile -> profile.withSocialInsuranceAtApplication(ApplicationTimeStatus.DECLARED_MET));
    }

    private static void assertOnlyChanges(String changedComponent, Function<CandidateProfile, CandidateProfile> change) {
        var before = populated();
        var after = change.apply(before);
        boolean sawChangedComponent = false;
        for (RecordComponent component : CandidateProfile.class.getRecordComponents()) {
            Object beforeValue = read(component, before);
            Object afterValue = read(component, after);
            if (component.getName().equals(changedComponent)) {
                sawChangedComponent = true;
                assertThat(afterValue).as("%s 应当被改写", component.getName()).isNotEqualTo(beforeValue);
            } else {
                assertThat(afterValue).as("%s 不应被这次修改影响", component.getName()).isEqualTo(beforeValue);
            }
        }
        assertThat(sawChangedComponent).as("%s 不是 CandidateProfile 的字段", changedComponent).isTrue();
    }

    private static Object read(RecordComponent component, CandidateProfile profile) {
        try {
            return component.getAccessor().invoke(profile);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("cannot read " + component.getName(), exception);
        }
    }
}
