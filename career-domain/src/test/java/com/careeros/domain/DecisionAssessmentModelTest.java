package com.careeros.domain;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DecisionAssessmentModelTest {
    private static final UUID CANDIDATE_ID = UUID.fromString("01992f09-0000-7000-8000-000000000001");
    private static final UUID JOB_ID = UUID.fromString("01992f09-0000-7000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    @Test
    void unknownDimensionCannotAwardPoints() {
        assertThatThrownBy(() -> new AssessmentDimension(
            AssessmentDimensionType.SKILL_FIT, 1, 20, AssessmentFactStatus.UNKNOWN,
            "SKILLS_UNKNOWN", "岗位或候选人技能缺失", List.of()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unknown");
    }

    @Test
    void fitAssessmentCalculatesScoreAndEvidenceCoverageFromLiteralWeights() {
        var assessment = new FitAssessment(
            UUID.randomUUID(), CANDIDATE_ID, JOB_ID,
            List.of(
                dimension(AssessmentDimensionType.MAJOR_FIT, 25, 25, AssessmentFactStatus.EXPLICIT),
                dimension(AssessmentDimensionType.SKILL_FIT, 0, 20, AssessmentFactStatus.UNKNOWN),
                dimension(AssessmentDimensionType.EXPERIENCE_FIT, 10, 20, AssessmentFactStatus.INTERPRETED),
                dimension(AssessmentDimensionType.RESEARCH_FIT, 0, 10, AssessmentFactStatus.UNKNOWN),
                dimension(AssessmentDimensionType.PROFESSIONAL_TITLE_FIT, 10, 10, AssessmentFactStatus.EXPLICIT),
                dimension(AssessmentDimensionType.PREFERENCE_FIT, 15, 15, AssessmentFactStatus.EXPLICIT)
            ),
            "fit-v1", "candidate-v2", "a".repeat(64), NOW
        );

        assertThat(assessment.score()).isEqualTo(60);
        assertThat(assessment.maximumScore()).isEqualTo(100);
        assertThat(assessment.coveragePercent()).isEqualTo(70);
        assertThat(assessment.dimensions()).isUnmodifiable();
    }

    @Test
    void decisionAssessmentRejectsScoresOutsideOneHundred() {
        assertThatThrownBy(() -> new DecisionAssessment(
            UUID.randomUUID(), CANDIDATE_ID, JOB_ID, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            EligibilityStatus.ELIGIBLE, OpportunityTier.T1, RecommendationStatus.RECOMMENDED,
            101, 80, 90, "decision-v1", "candidate-v2", "b".repeat(64), NOW
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("fitScore");
    }

    @Test
    void candidateDecisionAttributesAreImmutable() {
        var candidate = new CandidateProfile(
            CANDIDATE_ID, "候选人", new PartialDate(1992, 12, null), EducationLevel.MASTER,
            Set.of("计算机科学与技术"), 2018, 6, Set.of("中级"), List.of("杭州"),
            Set.of(EmploymentType.ESTABLISHMENT, EmploymentType.CONTRACT), "candidate-v2",
            Set.of("Java", "PostgreSQL"), Set.of("智能系统"), Set.of(JobFamily.SOFTWARE, JobFamily.DATA),
            Set.of(OrganizationType.PUBLIC_INSTITUTION, OrganizationType.STATE_OWNED_ENTERPRISE)
        );

        assertThat(candidate.skills()).containsExactlyInAnyOrder("Java", "PostgreSQL");
        assertThat(candidate.skills()).isUnmodifiable();
        assertThat(candidate.targetJobFamilies()).containsExactlyInAnyOrder(JobFamily.SOFTWARE, JobFamily.DATA);
    }

    private static AssessmentDimension dimension(
        AssessmentDimensionType type, int points, int maximum, AssessmentFactStatus status
    ) {
        return new AssessmentDimension(type, points, maximum, status, "TEST", "测试维度", List.of(UUID.randomUUID()));
    }
}
