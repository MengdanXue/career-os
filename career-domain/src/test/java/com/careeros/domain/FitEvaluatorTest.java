package com.careeros.domain;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FitEvaluatorTest {
    @Test
    void awardsLiteralMasterSpecWeightsForGroundedMatches() {
        var candidate = candidate(Set.of("Java", "PostgreSQL"), Set.of("数据治理"));
        var job = job(EmploymentType.ESTABLISHMENT, "负责Java、PostgreSQL平台与数据治理研究", List.of(UUID.randomUUID()));
        var organization = organization(OrganizationType.PUBLIC_INSTITUTION);

        var result = new FitEvaluator().evaluate(candidate, job, organization, "a".repeat(64), Instant.parse("2026-08-20T12:00:00Z"));

        assertThat(result.score()).isEqualTo(100);
        assertThat(result.coveragePercent()).isEqualTo(100);
        assertThat(result.dimensions()).extracting(AssessmentDimension::maximumPoints)
            .containsExactly(25, 20, 20, 10, 10, 15);
    }

    @Test
    void missingSkillsAndResearchStayUnknownAndAwardNoPoints() {
        var result = new FitEvaluator().evaluate(
            candidate(Set.of(), Set.of()), job(EmploymentType.ESTABLISHMENT, null, List.of(UUID.randomUUID())),
            organization(OrganizationType.PUBLIC_INSTITUTION), "b".repeat(64), Instant.parse("2026-08-20T12:00:00Z")
        );

        assertThat(result.dimensions().stream()
            .filter(d -> d.type() == AssessmentDimensionType.SKILL_FIT || d.type() == AssessmentDimensionType.RESEARCH_FIT))
            .allMatch(d -> d.factStatus() == AssessmentFactStatus.UNKNOWN && d.achievedPoints() == 0);
        assertThat(result.score()).isEqualTo(70);
        assertThat(result.coveragePercent()).isEqualTo(70);
    }

    @Test
    void storedButUnconfirmedSkillsDoNotAddPositiveFitPoints() {
        var candidate = candidate(Set.of("Java", "PostgreSQL"), Set.of("数据治理"));
        var result = new FitEvaluator().evaluate(
            candidate, CandidateFacts.resolve(candidate, List.of()),
            job(EmploymentType.ESTABLISHMENT, "负责Java、PostgreSQL平台与数据治理研究", List.of(UUID.randomUUID())),
            organization(OrganizationType.PUBLIC_INSTITUTION), "c".repeat(64), Instant.parse("2026-08-20T12:00:00Z")
        );

        assertThat(result.dimensions().stream()
            .filter(d -> d.type() == AssessmentDimensionType.SKILL_FIT || d.type() == AssessmentDimensionType.RESEARCH_FIT))
            .allMatch(d -> d.factStatus() == AssessmentFactStatus.UNKNOWN && d.achievedPoints() == 0);
    }

    @Test
    void candidateRejectsBlankDecisionKeywords() {
        assertThatThrownBy(() -> candidate(Set.of("  "), Set.of("数据治理")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("skills");
    }

    @Test
    void missingQualificationCutoffDoesNotUseAssessmentTimeForExperienceFit() {
        var candidate = candidate(Set.of("Java"), Set.of("数据治理"));
        var assessedAt = Instant.parse("2026-08-20T12:00:00Z");

        var result = new FitEvaluator().evaluate(candidate, CandidateFacts.confirmed(candidate),
            job(EmploymentType.ESTABLISHMENT, "Java 数据治理", List.of(UUID.randomUUID())),
            organization(OrganizationType.PUBLIC_INSTITUTION), "d".repeat(64), null, assessedAt);

        assertThat(result.dimensions()).filteredOn(d -> d.type() == AssessmentDimensionType.EXPERIENCE_FIT)
            .singleElement().satisfies(d -> {
                assertThat(d.factStatus()).isEqualTo(AssessmentFactStatus.UNKNOWN);
                assertThat(d.reasonCode()).isEqualTo("QUALIFICATION_CUTOFF_UNKNOWN");
            });
        assertThat(result.assessedAt()).isEqualTo(assessedAt);
    }

    @Test
    void legacyFormalEmploymentPreferenceCoversNewExplicitFormalIdentities() {
        var candidate = candidate(Set.of("Java"), Set.of("数据治理"));

        for (EmploymentType type : List.of(
            EmploymentType.QUOTA_OR_FILING, EmploymentType.UNIT_FORMAL, EmploymentType.SOE_FORMAL)) {
            var assessment = new FitEvaluator().evaluate(
                candidate, job(type, "Java 数据治理", List.of(UUID.randomUUID())),
                organization(OrganizationType.PUBLIC_INSTITUTION), "e".repeat(64),
                Instant.parse("2026-08-20T12:00:00Z"));

            assertThat(assessment.dimensions())
                .filteredOn(dimension -> dimension.type() == AssessmentDimensionType.PREFERENCE_FIT)
                .singleElement()
                .extracting(AssessmentDimension::achievedPoints)
                .isEqualTo(15);
        }
    }

    @Test
    void organizationLocationMatchesPreferenceWhenJobWorksiteIsUnknown() {
        var candidate = candidate(Set.of("Java"), Set.of("数据治理"));

        var assessment = new FitEvaluator().evaluate(
            candidate, job(EmploymentType.ESTABLISHMENT, "Java 数据治理", List.of(UUID.randomUUID()), null),
            organization(OrganizationType.PUBLIC_INSTITUTION), "f".repeat(64),
            Instant.parse("2026-08-20T12:00:00Z"));

        assertThat(assessment.dimensions())
            .filteredOn(dimension -> dimension.type() == AssessmentDimensionType.PREFERENCE_FIT)
            .singleElement()
            .extracting(AssessmentDimension::achievedPoints)
            .isEqualTo(15);
    }

    private static CandidateProfile candidate(Set<String> skills, Set<String> research) {
        return new CandidateProfile(
            UUID.randomUUID(), "候选人", new PartialDate(1992, 12, null), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), 2018, 6, Set.of("中级：计算机应用"), List.of("杭州"),
            Set.of(EmploymentType.ESTABLISHMENT), "candidate-v2", skills, research,
            Set.of(JobFamily.SOFTWARE), Set.of(OrganizationType.PUBLIC_INSTITUTION), List.of(),
            Gender.FEMALE, PoliticalAffiliation.NON_MEMBER,
            List.of(new CandidateEmploymentRecord("测试单位", "工程师",
                LocalDate.of(2018, 1, 1), LocalDate.of(2023, 12, 31),
                CandidateEmploymentRecord.EmploymentMode.FULL_TIME,
                CandidateEmploymentRecord.VerificationStatus.VERIFIED, Set.of("劳动合同")))
        );
    }

    private static JobPosting job(EmploymentType employmentType, String duties, List<UUID> evidenceIds) {
        return job(employmentType, duties, evidenceIds, "杭州市");
    }

    private static JobPosting job(
        EmploymentType employmentType, String duties, List<UUID> evidenceIds, String location
    ) {
        return new JobPosting(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "IT-01", "软件工程师", JobFamily.SOFTWARE,
            employmentType, location, 1, EducationLevel.MASTER, Set.of("计算机科学与技术"), Set.of(),
            null, null, 3, Set.of("中级：计算机应用"), duties, "https://example.gov.cn/job", evidenceIds
        );
    }

    private static Organization organization(OrganizationType type) {
        return new Organization(UUID.randomUUID(), "杭州市信息中心", type, "市级", "浙江", "杭州", null, null, "https://example.gov.cn");
    }
}
