package com.careeros;

import static com.careeros.domain.CandidateEmploymentRecord.EmploymentMode.FULL_TIME;
import static com.careeros.domain.CandidateEmploymentRecord.VerificationStatus.VERIFIED;
import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.domain.CandidateEmploymentRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CandidatePlanningProfileApiTest {
    @Test
    void candidateRequestPreservesPlanningFactsAndSerializesThemWithoutDocuments() throws Exception {
        var employment = new CandidateEmploymentRecord(
            "示例科技有限公司", "高级 Java 开发工程师",
            LocalDate.of(2020, 1, 1), LocalDate.of(2021, 3, 31), FULL_TIME, VERIFIED,
            Set.of("劳动合同", "社保记录"));
        var request = new CareerMvpController.CandidateRequest(
            "测试候选人", 1992, 12, 31, EducationLevel.BACHELOR, Set.of("计算机科学与技术"), 2014, null,
            Set.of("中级：计算机应用（评审）"), List.of("杭州", "浙江"),
            Set.of(EmploymentType.ESTABLISHMENT, EmploymentType.PUBLIC_INSTITUTION_FORMAL), "ignored",
            Set.of("Java"), Set.of("AI"), Set.of(JobFamily.SOFTWARE),
            Set.of(OrganizationType.PUBLIC_INSTITUTION), List.of(),
            Gender.FEMALE, PoliticalAffiliation.UNKNOWN, List.of(employment));

        var candidate = request.toDomain(UUID.fromString("01992f09-0000-7000-8000-000000000001"));
        var json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(candidate);

        assertThat(candidate.birthDate().day()).isEqualTo(31);
        assertThat(candidate.gender()).isEqualTo(Gender.FEMALE);
        assertThat(candidate.employmentRecords()).containsExactly(employment);
        assertThat(json).contains("\"gender\":\"FEMALE\"", "\"verificationStatus\":\"VERIFIED\"");
        assertThat(json).doesNotContain("documentContent", "documentBytes");
    }
}
