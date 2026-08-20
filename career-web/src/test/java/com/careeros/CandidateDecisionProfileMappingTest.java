package com.careeros;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CandidateDecisionProfileMappingTest {
    @Test void candidateApiPreservesDecisionIntelligenceInputs() {
        var request=new CareerMvpController.CandidateRequest(
            "候选人",1992,9,null,EducationLevel.MASTER,Set.of("计算机科学与技术"),2018,6,
            Set.of("中级"),List.of("杭州"),Set.of(EmploymentType.ESTABLISHMENT),"profile-v2",
            Set.of("Java","PostgreSQL"),Set.of("数据治理"),Set.of(JobFamily.SOFTWARE),Set.of(OrganizationType.PUBLIC_INSTITUTION)
        );

        var candidate=request.toDomain(UUID.randomUUID());

        assertThat(candidate.skills()).containsExactlyInAnyOrder("Java","PostgreSQL");
        assertThat(candidate.researchKeywords()).containsExactly("数据治理");
        assertThat(candidate.targetJobFamilies()).containsExactly(JobFamily.SOFTWARE);
        assertThat(candidate.preferredOrganizationTypes()).containsExactly(OrganizationType.PUBLIC_INSTITUTION);
    }
}
