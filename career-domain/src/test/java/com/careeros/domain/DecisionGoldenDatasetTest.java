package com.careeros.domain;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DecisionGoldenDatasetTest {
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    @Test
    void reviewedHangzhouSemiPublicCasesKeepTheirDecisionBoundaries() throws Exception {
        try (var stream = getClass().getResourceAsStream("/golden/decision-jobs.json")) {
            assertThat(stream).isNotNull();
            JsonNode cases = new ObjectMapper().readTree(stream).path("cases");
            assertThat(cases).hasSize(7);

            for (JsonNode fixture : cases) {
                var candidate = candidate(fixture.path("candidateMajor").asText());
                var organization = organization(OrganizationType.valueOf(fixture.path("organizationType").asText()));
                var job = job(
                    organization.id(),
                    EmploymentType.valueOf(fixture.path("employmentType").asText()),
                    fixture.path("jobMajor").asText(),
                    fixture.path("hasJobEvidence").asBoolean()
                );

                var eligibility = new EligibilityEvaluator().evaluate(
                    candidate, CandidateFacts.confirmed(candidate), job, "a".repeat(64),
                    LocalDate.ofInstant(NOW, java.time.ZoneOffset.UTC), NOW,
                    EligibilityEvaluator.VERSION, Set.of(),
                    graduateClause(fixture.path("announcementGraduateClause").asText()), java.util.Map.of());
                var stability = new StabilityEvaluator().evaluate(candidate, job, organization, List.of(), "a".repeat(64), NOW);
                OpportunityTier finalTier = eligibility.status() == EligibilityStatus.INELIGIBLE
                    ? OpportunityTier.EXCLUDED : stability.tier();

                assertThat(eligibility.status()).as(fixture.path("id").asText())
                    .isEqualTo(EligibilityStatus.valueOf(fixture.path("expectedEligibility").asText()));
                assertThat(finalTier).as(fixture.path("id").asText())
                    .isEqualTo(OpportunityTier.valueOf(fixture.path("expectedTier").asText()));
                assertThat(stability.assessment().coveragePercent()).as(fixture.path("id").asText())
                    .isEqualTo(fixture.path("expectedStabilityCoverage").asInt());
            }
        }
    }

    /**
     * 这些用例冻结时，"公告没提应届"是评审人的隐含前提。应届成为一条规则之后，前提必须显式：
     * 公告处理过且没有条款是可以下结论的事实，公告没采集到只能待确认——两者结论相反。
     */
    private static GraduateEligibilityRule graduateClause(String declared) {
        if ("NOT_COLLECTED".equals(declared)) return null;
        return new GraduateEligibilityRule(2026, Set.of(), Set.of(), false,
            GraduateEligibilityRule.RequirementTiming.UNSPECIFIED, null,
            GraduateEligibilityRule.RequirementTiming.UNSPECIFIED, null,
            false, false, "", GraduateEligibilityRule.EvidenceState.NOT_REQUIRED);
    }

    private static CandidateProfile candidate(String major) {
        return new CandidateProfile(
            UUID.randomUUID(), "候选人", new PartialDate(1992, 12, null), EducationLevel.MASTER,
            Set.of(major), 2018, 6, Set.of(), List.of("杭州"),
            Set.of(EmploymentType.ESTABLISHMENT, EmploymentType.CONTRACT), "golden-v1"
        );
    }

    private static Organization organization(OrganizationType type) {
        return new Organization(UUID.randomUUID(), "杭州样例单位", type, null, "浙江", "杭州", null, null, null);
    }

    private static JobPosting job(UUID organizationId, EmploymentType employmentType, String major, boolean hasEvidence) {
        List<UUID> evidence = hasEvidence ? List.of(UUID.randomUUID()) : List.of();
        return new JobPosting(
            UUID.randomUUID(), UUID.randomUUID(), organizationId, "GOLDEN", "信息技术岗",
            JobFamily.INFORMATION_SYSTEMS, employmentType, "杭州", 1, EducationLevel.BACHELOR,
            Set.of(major), Set.of(), 40, LocalDate.of(2026, 8, 20), 0, Set.of(), "Java 数据治理",
            "https://example.gov.cn/golden", evidence
        );
    }
}
