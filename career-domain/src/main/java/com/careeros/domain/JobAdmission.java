package com.careeros.domain;

import static com.careeros.domain.DomainEnums.DataQualityStatus.RAW;
import static com.careeros.domain.DomainEnums.DataQualityStatus.VERIFIED;
import static com.careeros.domain.DomainEnums.TargetScopeStatus.INCLUDED;
import static com.careeros.domain.DomainEnums.TargetScopeStatus.NEEDS_REVIEW;

import com.careeros.domain.DomainEnums.DataQualityStatus;
import com.careeros.domain.DomainEnums.JobAdmissionReason;
import com.careeros.domain.DomainEnums.TargetScopeStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record JobAdmission(
    UUID jobPostingId,
    DataQualityStatus dataQualityStatus,
    TargetScopeStatus targetScopeStatus,
    Set<JobAdmissionReason> reasonCodes,
    String evaluatorVersion,
    Instant assessedAt,
    boolean humanVerified
) {
    public JobAdmission {
        Objects.requireNonNull(jobPostingId, "jobPostingId");
        Objects.requireNonNull(dataQualityStatus, "dataQualityStatus");
        Objects.requireNonNull(targetScopeStatus, "targetScopeStatus");
        reasonCodes = reasonCodes == null ? Set.of() : Set.copyOf(reasonCodes);
        if (evaluatorVersion == null || evaluatorVersion.isBlank()) {
            throw new IllegalArgumentException("evaluatorVersion must not be blank");
        }
        Objects.requireNonNull(assessedAt, "assessedAt");
    }

    public static JobAdmission raw(UUID jobId, Instant now, JobAdmissionReason reason) {
        return new JobAdmission(jobId, RAW, NEEDS_REVIEW, Set.of(reason), "admission-v1", now, false);
    }

    public boolean admitted() {
        return dataQualityStatus == VERIFIED && targetScopeStatus == INCLUDED;
    }
}
