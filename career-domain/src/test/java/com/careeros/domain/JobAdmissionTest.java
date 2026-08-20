package com.careeros.domain;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobAdmissionTest {
    private static final UUID JOB_ID = UUID.fromString("01992f09-0000-7000-8000-000000000901");
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    @Test
    void onlyVerifiedIncludedJobsAreAdmitted() {
        assertThat(admission(DataQualityStatus.VERIFIED, TargetScopeStatus.INCLUDED).admitted()).isTrue();
        assertThat(admission(DataQualityStatus.RAW, TargetScopeStatus.INCLUDED).admitted()).isFalse();
        assertThat(admission(DataQualityStatus.VERIFIED, TargetScopeStatus.NEEDS_REVIEW).admitted()).isFalse();
        assertThat(admission(DataQualityStatus.VERIFIED, TargetScopeStatus.EXCLUDED).admitted()).isFalse();
    }

    @Test
    void rawFactoryNeverClaimsHumanVerification() {
        JobAdmission value = JobAdmission.raw(JOB_ID, NOW, JobAdmissionReason.LEGACY_UNVERIFIED);

        assertThat(value.dataQualityStatus()).isEqualTo(DataQualityStatus.RAW);
        assertThat(value.targetScopeStatus()).isEqualTo(TargetScopeStatus.NEEDS_REVIEW);
        assertThat(value.reasonCodes()).containsExactly(JobAdmissionReason.LEGACY_UNVERIFIED);
        assertThat(value.humanVerified()).isFalse();
        assertThat(value.admitted()).isFalse();
    }

    @Test
    void decisionReadinessAlsoRequiresVerifiedEmploymentIdentity() {
        JobAdmission admission = admission(DataQualityStatus.VERIFIED, TargetScopeStatus.INCLUDED);
        JobPosting known = job(EmploymentType.ESTABLISHMENT);
        JobPosting unknown = job(EmploymentType.UNKNOWN);

        assertThat(admission.admits(known)).isTrue();
        assertThat(admission.admits(unknown)).isFalse();
    }

    private static JobAdmission admission(DataQualityStatus quality, TargetScopeStatus scope) {
        return new JobAdmission(
            JOB_ID, quality, scope, Set.of(JobAdmissionReason.TARGET_TECHNICAL_ROLE),
            "admission-v1", NOW, true);
    }

    private static JobPosting job(EmploymentType employmentType) {
        return new JobPosting(JOB_ID, UUID.randomUUID(), UUID.randomUUID(), "A01", "信息岗",
            JobFamily.INFORMATION_SYSTEMS, employmentType, "杭州", 1, EducationLevel.BACHELOR,
            Set.of(), Set.of(), null, null, null, Set.of(), null,
            "https://example.gov.cn/job", java.util.List.of());
    }
}
