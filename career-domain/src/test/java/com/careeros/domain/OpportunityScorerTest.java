package com.careeros.domain;

import com.careeros.domain.DomainEnums.*;
import com.careeros.domain.OpportunityScorecard.DimensionScore;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpportunityScorerTest {
    private final OpportunityScorer scorer = new OpportunityScorer();
    private final OpportunityTierClassifier classifier = new OpportunityTierClassifier();
    private final EligibilityEvaluator evaluator = new EligibilityEvaluator();
    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

    /** §6.2 的核心约束：没有总分字段，六维必须各自可见。 */
    @Test void scorecardExposesEverySixDimensionAndNoTotal() {
        var card = score(EmploymentType.ESTABLISHMENT, OrganizationType.HOSPITAL, JobFamily.SOFTWARE);
        assertThat(card.dimensions()).containsOnlyKeys(ScoreDimension.values());
        assertThat(OpportunityScorecard.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .doesNotContain("totalScore", "matchScore", "overallScore");
    }

    /** 缺数据的维度必须自报缺数据，而不是给 0 分。 */
    @Test void dimensionsWithoutDataAreDeclaredRatherThanScoredZero() {
        var card = score(EmploymentType.ESTABLISHMENT, OrganizationType.HOSPITAL, JobFamily.SOFTWARE);
        assertThat(card.dimensionsWithoutData())
            .containsExactly(ScoreDimension.FUTURE, ScoreDimension.PREPARATION_COST);
        for (ScoreDimension dimension : card.dimensionsWithoutData()) {
            assertThat(card.dimensions().get(dimension).value()).isNull();
            assertThat(card.dimensions().get(dimension).rationale()).isNotBlank();
        }
    }

    @Test void everyScoredDimensionDeclaresItsBasisAndReason() {
        var card = score(EmploymentType.ESTABLISHMENT, OrganizationType.HOSPITAL, JobFamily.AI);
        card.dimensions().forEach((dimension, score) -> {
            assertThat(score.basis()).isNotNull();
            assertThat(score.rationale()).as("%s rationale", dimension).isNotBlank();
        });
    }

    @Test void insufficientDataMustNotCarryAValue() {
        assertThatThrownBy(() -> new DimensionScore(50, ScoreBasis.INSUFFICIENT_DATA, "无依据"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DimensionScore(null, ScoreBasis.ESTIMATED, "有依据"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void scorecardRejectsAMissingDimension() {
        var partial = OpportunityScorecard.emptyDimensions();
        partial.put(ScoreDimension.FIT, DimensionScore.estimated(50, "只有一维"));
        assertThatThrownBy(() -> new OpportunityScorecard(
            UUID.randomUUID(), partial, StrategyGrade.APPLY, "理由", "v1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing dimension");
    }

    // --- 策略等级的推导规则 ---

    @Test void ineligibleIsRejectedRegardlessOfTier() {
        var grade = OpportunityScorer.grade(EligibilityStatus.INELIGIBLE, tier(OpportunityTier.T1_ESTABLISHMENT_TARGET, true),
            DimensionScore.estimated(100, "满分"));
        assertThat(grade.value()).isEqualTo(StrategyGrade.REJECT);
    }

    @Test void conflictingEvidenceIsRejected() {
        assertThat(OpportunityScorer.grade(EligibilityStatus.CONFLICTING_EVIDENCE,
            tier(OpportunityTier.T1_ESTABLISHMENT_TARGET, true), DimensionScore.estimated(100, "满分")).value())
            .isEqualTo(StrategyGrade.REJECT);
    }

    @Test void excludedTierIsRejectedEvenWhenEligible() {
        assertThat(OpportunityScorer.grade(EligibilityStatus.ELIGIBLE, tier(OpportunityTier.EXCLUDED, true),
            DimensionScore.estimated(100, "满分")).value())
            .isEqualTo(StrategyGrade.REJECT);
    }

    /** 未确认与条件满足都停在 VERIFY_FIRST，不因为分池好看就放行。 */
    @Test void unresolvedEligibilityStopsAtVerifyFirst() {
        for (EligibilityStatus status : List.of(EligibilityStatus.NEEDS_CONFIRMATION, EligibilityStatus.CONDITIONAL)) {
            assertThat(OpportunityScorer.grade(status, tier(OpportunityTier.T1_ESTABLISHMENT_TARGET, true),
                DimensionScore.estimated(100, "满分")).value())
                .isEqualTo(StrategyGrade.VERIFY_FIRST);
        }
    }

    @Test void insufficientTierEvidenceStopsAtVerifyFirst() {
        assertThat(OpportunityScorer.grade(EligibilityStatus.ELIGIBLE, tier(OpportunityTier.T2_IDENTITY_REVIEW, false),
            DimensionScore.estimated(100, "满分")).value())
            .isEqualTo(StrategyGrade.VERIFY_FIRST);
    }

    @Test void eligibleTiersMapToTheirActionGrade() {
        assertThat(OpportunityScorer.grade(EligibilityStatus.ELIGIBLE, tier(OpportunityTier.T1_ESTABLISHMENT_TARGET, true),
            DimensionScore.estimated(OpportunityScorer.MUST_TRACK_FIT_THRESHOLD, "达标")).value())
            .isEqualTo(StrategyGrade.MUST_TRACK);
        assertThat(OpportunityScorer.grade(EligibilityStatus.ELIGIBLE, tier(OpportunityTier.T2_IDENTITY_REVIEW, true),
            DimensionScore.estimated(90, "高")).value())
            .isEqualTo(StrategyGrade.APPLY);
        assertThat(OpportunityScorer.grade(EligibilityStatus.ELIGIBLE, tier(OpportunityTier.T3_STABLE_SOE_BACKUP, true),
            DimensionScore.estimated(90, "高")).value())
            .isEqualTo(StrategyGrade.BACKUP);
    }

    /** 分池好不代表自动主攻：T1 但匹配度不达标只能是 APPLY。 */
    @Test void tierAloneDoesNotPromoteToMustTrack() {
        assertThat(OpportunityScorer.grade(EligibilityStatus.ELIGIBLE, tier(OpportunityTier.T1_ESTABLISHMENT_TARGET, true),
            DimensionScore.estimated(OpportunityScorer.MUST_TRACK_FIT_THRESHOLD - 1, "偏低")).value())
            .isEqualTo(StrategyGrade.APPLY);
    }

    @Test void everyGradeCarriesARationale() {
        for (EligibilityStatus status : EligibilityStatus.values()) {
            for (OpportunityTier tierValue : OpportunityTier.values()) {
                for (boolean sufficient : List.of(true, false)) {
                    if (tierValue == OpportunityTier.T1_ESTABLISHMENT_TARGET && !sufficient) continue;
                    var grade = OpportunityScorer.grade(status, tier(tierValue, sufficient),
                        DimensionScore.estimated(70, "测试"));
                    assertThat(grade.value()).isNotNull();
                    assertThat(grade.rationale()).as("%s/%s/%s", status, tierValue, sufficient).isNotBlank();
                }
            }
        }
    }

    /** 稳定性必须按用工身份区分开，否则分池就白做了。 */
    @Test void stabilityRanksEmploymentIdentities() {
        int establishment = dimension(EmploymentType.ESTABLISHMENT, OrganizationType.HOSPITAL, ScoreDimension.STABILITY);
        int headcount = dimension(EmploymentType.AUTHORIZED_HEADCOUNT, OrganizationType.UNIVERSITY, ScoreDimension.STABILITY);
        int contract = dimension(EmploymentType.CONTRACT, OrganizationType.UNIVERSITY, ScoreDimension.STABILITY);
        assertThat(establishment).isGreaterThan(headcount);
        assertThat(headcount).isGreaterThan(contract);
    }

    private int dimension(EmploymentType employment, OrganizationType organizationType, ScoreDimension dimension) {
        return score(employment, organizationType, JobFamily.SOFTWARE).dimensions().get(dimension).value();
    }

    private OpportunityScorecard score(EmploymentType employment, OrganizationType organizationType, JobFamily family) {
        var candidate = new CandidateProfile(UUID.randomUUID(), "test", PartialDate.month(1992, 12),
            EducationLevel.MASTER, Set.of("计算机科学与技术"), 2020, 5, Set.of(), List.of("杭州"),
            Set.of(EmploymentType.ESTABLISHMENT), "test-v1");
        var job = new JobPosting(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "TEST", "测试岗位",
            family, employment, "杭州", 2, EducationLevel.MASTER, Set.of("计算机科学与技术"), Set.of(),
            40, LocalDate.of(2026, 1, 1), null, Set.of(), "系统建设", "https://example.test/official", List.of(), Map.of());
        var organization = new Organization(UUID.randomUUID(), "测试单位", organizationType, null, "浙江", "杭州", null, null, null);
        var assessment = evaluator.evaluate(candidate, job, NOW);
        return scorer.score(candidate, job, organization, assessment, classifier.classify(job, organization));
    }

    private OpportunityTierAssessment tier(OpportunityTier value, boolean evidenceSufficient) {
        return new OpportunityTierAssessment(UUID.randomUUID(), value, EmploymentType.ESTABLISHMENT,
            OrganizationType.PUBLIC_INSTITUTION, evidenceSufficient, "测试理由", List.of());
    }
}
