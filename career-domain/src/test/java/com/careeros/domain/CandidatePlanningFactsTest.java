package com.careeros.domain;

import static com.careeros.domain.CandidateEmploymentRecord.EmploymentMode.FULL_TIME;
import static com.careeros.domain.CandidateEmploymentRecord.VerificationStatus.VERIFIED;
import static com.careeros.domain.CandidateFacts.CandidateFactKey.EMPLOYMENT_HISTORY;
import static com.careeros.domain.CandidateFacts.CandidateFactKey.GENDER;
import static com.careeros.domain.CandidateFacts.CandidateFactStatus.UNCONFIRMED;
import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CandidatePlanningFactsTest {
    private static final UUID ID = UUID.fromString("01992f09-0000-7000-8000-000000000001");

    @Test
    void employmentHistoryAndGenderAreFirstClassCandidateFacts() {
        var candidate = candidateWith(List.of(verifiedEmployment()), Gender.FEMALE);

        assertThat(CandidateFacts.fingerprint(candidate, GENDER)).hasSize(64);
        assertThat(CandidateFacts.fingerprint(candidate, EMPLOYMENT_HISTORY)).hasSize(64);
        assertThat(CandidateFacts.resolve(candidate, List.of()).status(GENDER)).isEqualTo(UNCONFIRMED);
        assertThat(CandidateFacts.resolve(candidate, List.of()).status(EMPLOYMENT_HISTORY)).isEqualTo(UNCONFIRMED);
    }

    @Test
    void rejectsAnEmploymentRecordWhoseEndPrecedesItsStart() {
        assertThatThrownBy(() -> new CandidateEmploymentRecord(
            "浙江示例科技有限公司", "Java 工程师", LocalDate.of(2022, 1, 1), LocalDate.of(2021, 12, 31),
            FULL_TIME, VERIFIED, Set.of("劳动合同")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("endsOn");
    }

    @Test
    void copiesEmploymentEvidenceInsteadOfRetainingMutableCollections() {
        var evidence = new ArrayList<>(List.of("劳动合同"));
        var record = new CandidateEmploymentRecord(
            "浙江示例科技有限公司", "Java 工程师", LocalDate.of(2020, 1, 1), LocalDate.of(2021, 3, 31),
            FULL_TIME, VERIFIED, Set.copyOf(evidence));
        evidence.add("社保记录");

        assertThat(record.evidenceTypes()).containsExactly("劳动合同");
        assertThatThrownBy(() -> record.evidenceTypes().add("单位证明"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void planningFactFingerprintsChangeWhenTheUnderlyingValueChanges() {
        var female = candidateWith(List.of(verifiedEmployment()), Gender.FEMALE);
        var unknownGender = candidateWith(List.of(verifiedEmployment()), Gender.UNKNOWN);
        var noEmployment = candidateWith(List.of(), Gender.FEMALE);

        assertThat(CandidateFacts.fingerprint(female, GENDER))
            .isNotEqualTo(CandidateFacts.fingerprint(unknownGender, GENDER));
        assertThat(CandidateFacts.fingerprint(female, EMPLOYMENT_HISTORY))
            .isNotEqualTo(CandidateFacts.fingerprint(noEmployment, EMPLOYMENT_HISTORY));
    }

    private static CandidateEmploymentRecord verifiedEmployment() {
        return new CandidateEmploymentRecord(
            "示例科技有限公司", "高级 Java 开发工程师",
            LocalDate.of(2020, 1, 1), LocalDate.of(2021, 3, 31), FULL_TIME, VERIFIED,
            Set.of("劳动合同", "社保记录"));
    }

    private static CandidateProfile candidateWith(List<CandidateEmploymentRecord> employments, Gender gender) {
        return new CandidateProfile(
            ID, "测试候选人", new PartialDate(1992, 12, 31), EducationLevel.BACHELOR,
            Set.of("计算机科学与技术"), 2014, null, Set.of("中级：计算机应用（评审）"),
            List.of("杭州", "浙江"), Set.of(EmploymentType.ESTABLISHMENT, EmploymentType.PUBLIC_INSTITUTION_FORMAL),
            "profile-planning-test", Set.of("Java"), Set.of("AI"), Set.of(JobFamily.SOFTWARE),
            Set.of(OrganizationType.PUBLIC_INSTITUTION), List.of(), gender, PoliticalAffiliation.UNKNOWN, employments);
    }
}
