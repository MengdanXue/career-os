package com.careeros.application;

import com.careeros.domain.DomainEnums.DataQualityStatus;
import com.careeros.domain.DomainEnums.TargetScopeStatus;
import com.careeros.domain.JobAdmission;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class JobAdmissionPorts {
    private JobAdmissionPorts() {}

    public interface JobAdmissions {
        Optional<JobAdmission> findByJobId(UUID jobId);

        default Optional<JobAdmission> findByJobIdForUpdate(UUID jobId) { return findByJobId(jobId); }

        JobAdmission save(JobAdmission value);

        AdmissionSummary summarize();
    }

    public record AdmissionSummary(
        long total,
        Map<DataQualityStatus, Long> byQuality,
        Map<TargetScopeStatus, Long> byTargetScope,
        long opportunityReady
    ) {
        public AdmissionSummary {
            if (total < 0 || opportunityReady < 0) {
                throw new IllegalArgumentException("summary counts must not be negative");
            }
            byQuality = byQuality == null ? Map.of() : Map.copyOf(byQuality);
            byTargetScope = byTargetScope == null ? Map.of() : Map.copyOf(byTargetScope);
        }

        public long count(DataQualityStatus status) {
            return byQuality.getOrDefault(status, 0L);
        }

        public long count(TargetScopeStatus status) {
            return byTargetScope.getOrDefault(status, 0L);
        }
    }
}
