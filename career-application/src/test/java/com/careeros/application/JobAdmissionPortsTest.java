package com.careeros.application;

import static com.careeros.domain.DomainEnums.DataQualityStatus.RAW;
import static com.careeros.domain.DomainEnums.TargetScopeStatus.NEEDS_REVIEW;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.JobAdmissionPorts.AdmissionSummary;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JobAdmissionPortsTest {

    @Test
    void summaryDefensivelyCopiesCountsAndReturnsZeroForMissingStatuses() {
        Map<com.careeros.domain.DomainEnums.DataQualityStatus, Long> quality = new HashMap<>();
        quality.put(RAW, 12L);

        AdmissionSummary summary = new AdmissionSummary(
            12L,
            quality,
            Map.of(NEEDS_REVIEW, 12L),
            0L
        );
        quality.put(RAW, 99L);

        assertThat(summary.count(RAW)).isEqualTo(12L);
        assertThat(summary.count(com.careeros.domain.DomainEnums.DataQualityStatus.VERIFIED)).isZero();
        assertThat(summary.count(NEEDS_REVIEW)).isEqualTo(12L);
        assertThat(summary.count(com.careeros.domain.DomainEnums.TargetScopeStatus.INCLUDED)).isZero();
    }
}
