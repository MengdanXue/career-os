package com.careeros.domain;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PoliticalRequirementClassifierTest {
    private final PoliticalRequirementClassifier classifier = new PoliticalRequirementClassifier();

    @Test void recognizesExplicitHardMembershipRequirements() {
        assertThat(classifier.hasHardRequirement(job("限中共党员", null, null))).isTrue();
        assertThat(classifier.hasHardRequirement(job(null, "政治面貌为中共党员", null))).isTrue();
        assertThat(classifier.hasHardRequirement(job(null, null, "报考人员须为中共党员"))).isTrue();
        assertThat(classifier.hasHardRequirement(job(null, "中共党员，年龄不限", null))).isTrue();
        assertThat(classifier.hasHardRequirement(job(null, "中共党员，具有相关经验者优先", null))).isTrue();
    }

    @Test void ignoresPreferencesAndExplicitlyUnrestrictedFields() {
        assertThat(classifier.hasHardRequirement(job(null, "中共党员优先", null))).isFalse();
        assertThat(classifier.hasHardRequirement(job(null, "政治面貌不限", null))).isFalse();
        assertThat(classifier.classify(job(null, "中共党员优先", null)))
            .isEqualTo(PoliticalRequirementClassifier.Classification.NO_HARD_REQUIREMENT);
        assertThat(classifier.classify(job(null, "政治面貌不限", null)))
            .isEqualTo(PoliticalRequirementClassifier.Classification.NO_HARD_REQUIREMENT);
    }

    @Test void alternativeAffiliationsNeedReviewRatherThanBeingReadAsUnrestricted() {
        for (String text : List.of("中共党员或民主党派", "须为中共党员或民主党派成员", "须为民主党派成员")) {
            assertThat(classifier.classify(job(null, text, null))).as(text)
                .isEqualTo(PoliticalRequirementClassifier.Classification.MANUAL_REVIEW);
            assertThat(classifier.hasHardRequirement(job(null, text, null))).as(text).isFalse();
        }
    }

    @Test void anAmbiguousClauseIsNotOverriddenByAnotherSimpleMembershipClause() {
        assertThat(classifier.classify(job("限中共党员", "党员或民主党派", null)))
            .isEqualTo(PoliticalRequirementClassifier.Classification.MANUAL_REVIEW);
    }

    @Test void alternativeMembershipAsAPreferenceStillDoesNotCreateAHardGate() {
        assertThat(classifier.classify(job(null, "中共党员或民主党派成员优先", null)))
            .isEqualTo(PoliticalRequirementClassifier.Classification.NO_HARD_REQUIREMENT);
    }

    @Test void unrelatedUnrestrictedClauseDoesNotCancelAHardPoliticalClause() {
        assertThat(classifier.hasHardRequirement(job(null, "限中共党员；年龄不限", null))).isTrue();
        assertThat(classifier.hasHardRequirement(job("限中共党员", "专业不限", null))).isTrue();
    }

    private static JobPosting job(String candidateScope, String otherRequirements, String original) {
        return new JobPosting(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "T-01",
            "信息岗位", JobFamily.INFORMATION_SYSTEMS, EmploymentType.ESTABLISHMENT, "杭州", 1,
            EducationLevel.BACHELOR, Set.of(), Set.of(), null, null, null, Set.of(), "",
            "https://example.gov.cn/job", List.of(), null, null, null, null, null, null,
            null, null, candidateScope, otherRequirements, original, null, null, null);
    }
}
