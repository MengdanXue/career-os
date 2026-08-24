package com.careeros.application.personal;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.domain.JobPosting;
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
    }

    @Test void ignoresPreferencesUnrestrictedFieldsAndAlternativeAffiliations() {
        assertThat(classifier.hasHardRequirement(job(null, "中共党员优先", null))).isFalse();
        assertThat(classifier.hasHardRequirement(job(null, "政治面貌不限", null))).isFalse();
        assertThat(classifier.hasHardRequirement(job(null, "中共党员或民主党派", null))).isFalse();
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
