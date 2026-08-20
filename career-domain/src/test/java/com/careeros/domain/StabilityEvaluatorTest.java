package com.careeros.domain;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StabilityEvaluatorTest {
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    @Test
    void explicitEstablishmentEvidenceProducesT1WithoutInventingOrganizationFacts() {
        var evidenceId = UUID.randomUUID();
        var job = job(EmploymentType.ESTABLISHMENT, List.of(evidenceId));

        var result = new StabilityEvaluator().evaluate(candidate(), job, organization(OrganizationType.PUBLIC_INSTITUTION), List.of(), "a".repeat(64), NOW);

        assertThat(result.assessment().score()).isEqualTo(40);
        assertThat(result.assessment().coveragePercent()).isEqualTo(40);
        assertThat(result.tier()).isEqualTo(OpportunityTier.T1);
        assertThat(result.assessment().dimensions().stream()
            .filter(d -> d.type() == AssessmentDimensionType.FUNDING_STABILITY))
            .allMatch(d -> d.factStatus() == AssessmentFactStatus.UNKNOWN && d.achievedPoints() == 0);
    }

    @Test
    void dispatchAndProjectWorkNeverExceedT3() {
        var evaluator = new StabilityEvaluator();

        assertThat(evaluator.evaluate(candidate(), job(EmploymentType.LABOR_DISPATCH, List.of(UUID.randomUUID())), organization(OrganizationType.STATE_OWNED_ENTERPRISE), List.of(), "b".repeat(64), NOW).tier())
            .isEqualTo(OpportunityTier.T3);
        assertThat(evaluator.evaluate(candidate(), job(EmploymentType.PROJECT_BASED, List.of(UUID.randomUUID())), organization(OrganizationType.PUBLIC_INSTITUTION), List.of(), "c".repeat(64), NOW).tier())
            .isEqualTo(OpportunityTier.T3);
    }

    @Test
    void evidenceBackedOrganizationFactContributesOnlyItsDeclaredDimension() {
        var organization = organization(OrganizationType.STATE_OWNED_ENTERPRISE);
        var fact = new OrganizationStabilityFact(
            UUID.randomUUID(), organization.id(), AssessmentDimensionType.FUNDING_STABILITY,
            12, 15, "STATE_SHAREHOLDER", "国资控股证据", List.of(UUID.randomUUID()), NOW
        );

        var result = new StabilityEvaluator().evaluate(candidate(), job(EmploymentType.CONTRACT, List.of(UUID.randomUUID())), organization, List.of(fact), "d".repeat(64), NOW);

        assertThat(result.assessment().dimensions().stream()
            .filter(d -> d.type() == AssessmentDimensionType.FUNDING_STABILITY)
            .mapToInt(AssessmentDimension::achievedPoints).sum()).isEqualTo(12);
        assertThat(result.tier()).isEqualTo(OpportunityTier.T2);
    }

    private static CandidateProfile candidate() {
        return new CandidateProfile(UUID.randomUUID(), "候选人", new PartialDate(1992, 12, null), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), 2018, 6, Set.of(), List.of("杭州"), Set.of(EmploymentType.ESTABLISHMENT, EmploymentType.CONTRACT), "v2");
    }

    private static JobPosting job(EmploymentType type, List<UUID> evidenceIds) {
        return new JobPosting(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "1", "技术岗", JobFamily.INFORMATION_SYSTEMS,
            type, "杭州", 1, EducationLevel.BACHELOR, Set.of(), Set.of(), null, null, null, Set.of(), null,
            "https://example.gov.cn", evidenceIds);
    }

    private static Organization organization(OrganizationType type) {
        return new Organization(UUID.randomUUID(), "单位", type, null, "浙江", "杭州", null, null, null);
    }
}
