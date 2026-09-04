package com.careeros.domain;

import com.careeros.domain.DomainEnums.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpportunityTierClassifierTest {
    private final OpportunityTierClassifier classifier = new OpportunityTierClassifier();

    @Test void explicitEstablishmentAtPublicOrganizationIsT1() {
        var result = classify(EmploymentType.ESTABLISHMENT, OrganizationType.HOSPITAL);
        assertThat(result.tier()).isEqualTo(OpportunityTier.T1_ESTABLISHMENT_TARGET);
        assertThat(result.evidenceSufficient()).isTrue();
    }

    /** §3：国企可以是稳定平台，但不得标成事业编。 */
    @Test void stateOwnedEnterpriseNeverReachesT1() {
        for (EmploymentType employment : List.of(EmploymentType.UNKNOWN, EmploymentType.CONTRACT, EmploymentType.PERSONNEL_AGENCY)) {
            var result = classify(employment, OrganizationType.STATE_OWNED_ENTERPRISE);
            assertThat(result.tier()).isEqualTo(OpportunityTier.T3_STABLE_SOE_BACKUP);
            assertThat(result.reason()).contains("不得标成事业编");
        }
    }

    /** 用工身份与单位类型互相矛盾时不能直接采信公告，降到 T2 等人工复核。 */
    @Test void establishmentAtNonPublicOrganizationFallsToIdentityReview() {
        var result = classify(EmploymentType.ESTABLISHMENT, OrganizationType.STATE_OWNED_ENTERPRISE);
        assertThat(result.tier()).isEqualTo(OpportunityTier.T2_IDENTITY_REVIEW);
        assertThat(result.evidenceSufficient()).isFalse();
    }

    @Test void defaultExclusionsAreExcludedRegardlessOfOrganization() {
        assertThat(classify(EmploymentType.LABOR_DISPATCH, OrganizationType.PUBLIC_INSTITUTION).tier())
            .isEqualTo(OpportunityTier.EXCLUDED);
        assertThat(classify(EmploymentType.PROJECT_BASED, OrganizationType.UNIVERSITY).tier())
            .isEqualTo(OpportunityTier.EXCLUDED);
    }

    @Test void publicInstitutionContractRolesNeedIdentityReview() {
        assertThat(classify(EmploymentType.CONTRACT, OrganizationType.RESEARCH_INSTITUTE).tier())
            .isEqualTo(OpportunityTier.T2_IDENTITY_REVIEW);
        assertThat(classify(EmploymentType.PERSONNEL_AGENCY, OrganizationType.UNIVERSITY).tier())
            .isEqualTo(OpportunityTier.T2_IDENTITY_REVIEW);
    }

    /** 公告没写用工身份时不猜：公共单位进 T2 待核验，其余进 UNKNOWN，都不会进 T1。 */
    @Test void missingEmploymentIdentityIsNeverPromoted() {
        var publicResult = classify(EmploymentType.UNKNOWN, OrganizationType.PUBLIC_INSTITUTION);
        assertThat(publicResult.tier()).isEqualTo(OpportunityTier.T2_IDENTITY_REVIEW);
        assertThat(publicResult.evidenceSufficient()).isFalse();

        var otherResult = classify(EmploymentType.UNKNOWN, OrganizationType.OTHER);
        assertThat(otherResult.tier()).isEqualTo(OpportunityTier.UNKNOWN);
        assertThat(otherResult.evidenceSufficient()).isFalse();
    }

    @Test void nonPublicContractRolesAreExcluded() {
        assertThat(classify(EmploymentType.CONTRACT, OrganizationType.OTHER).tier())
            .isEqualTo(OpportunityTier.EXCLUDED);
    }

    @Test void assessmentCarriesJobEvidence() {
        UUID evidenceId = UUID.randomUUID();
        var job = job(EmploymentType.ESTABLISHMENT, List.of(evidenceId));
        var result = classifier.classify(job, organization(OrganizationType.PUBLIC_INSTITUTION));
        assertThat(result.evidenceIds()).containsExactly(evidenceId);
        assertThat(result.jobPostingId()).isEqualTo(job.id());
    }

    @Test void t1CannotBeRecordedWithoutSufficientEvidence() {
        assertThatThrownBy(() -> new OpportunityTierAssessment(
            UUID.randomUUID(), OpportunityTier.T1_ESTABLISHMENT_TARGET, EmploymentType.ESTABLISHMENT,
            OrganizationType.PUBLIC_INSTITUTION, false, "证据不足", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private OpportunityTierAssessment classify(EmploymentType employment, OrganizationType organizationType) {
        return classifier.classify(job(employment, List.of()), organization(organizationType));
    }

    private Organization organization(OrganizationType type) {
        return new Organization(UUID.randomUUID(), "测试单位", type, null, "浙江", "杭州", null, null, null);
    }

    private JobPosting job(EmploymentType employment, List<UUID> evidenceIds) {
        return new JobPosting(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "TEST", "测试岗位",
            JobFamily.SOFTWARE, employment, "杭州", 1, EducationLevel.MASTER, Set.of(), Set.of(),
            null, null, null, Set.of(), "", "https://example.test/official", evidenceIds);
    }
}
