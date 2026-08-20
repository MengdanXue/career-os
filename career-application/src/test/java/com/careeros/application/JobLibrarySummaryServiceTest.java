package com.careeros.application;

import static com.careeros.domain.DomainEnums.DataQualityStatus.RAW;
import static com.careeros.domain.DomainEnums.TargetScopeStatus.NEEDS_REVIEW;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.JobAdmissionPorts.AdmissionSummary;
import com.careeros.application.JobAdmissionPorts.JobAdmissions;
import com.careeros.domain.JobAdmission;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobLibrarySummaryServiceTest {

    @Test
    void mapsAdmissionInventoryToExplicitZeroPreservingReadModel() {
        JobAdmissions admissions = new JobAdmissions() {
            public Optional<JobAdmission> findByJobId(UUID jobId) { return Optional.empty(); }
            public JobAdmission save(JobAdmission value) { throw new UnsupportedOperationException(); }
            public AdmissionSummary summarize() {
                return new AdmissionSummary(2291, Map.of(RAW, 2291L), Map.of(NEEDS_REVIEW, 2291L), 0);
            }
        };

        var summary = new JobLibrarySummaryService(admissions).load();

        assertThat(summary.total()).isEqualTo(2291);
        assertThat(summary.raw()).isEqualTo(2291);
        assertThat(summary.parsed()).isZero();
        assertThat(summary.normalized()).isZero();
        assertThat(summary.reviewRequired()).isZero();
        assertThat(summary.verified()).isZero();
        assertThat(summary.rejected()).isZero();
        assertThat(summary.failed()).isZero();
        assertThat(summary.included()).isZero();
        assertThat(summary.excluded()).isZero();
        assertThat(summary.needsReview()).isEqualTo(2291);
        assertThat(summary.opportunityReady()).isZero();
    }
}
