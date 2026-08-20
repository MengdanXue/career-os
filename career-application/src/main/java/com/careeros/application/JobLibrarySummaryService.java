package com.careeros.application;

import static com.careeros.domain.DomainEnums.DataQualityStatus.FAILED;
import static com.careeros.domain.DomainEnums.DataQualityStatus.NORMALIZED;
import static com.careeros.domain.DomainEnums.DataQualityStatus.PARSED;
import static com.careeros.domain.DomainEnums.DataQualityStatus.RAW;
import static com.careeros.domain.DomainEnums.DataQualityStatus.REJECTED;
import static com.careeros.domain.DomainEnums.DataQualityStatus.REVIEW_REQUIRED;
import static com.careeros.domain.DomainEnums.DataQualityStatus.VERIFIED;
import static com.careeros.domain.DomainEnums.TargetScopeStatus.EXCLUDED;
import static com.careeros.domain.DomainEnums.TargetScopeStatus.INCLUDED;
import static com.careeros.domain.DomainEnums.TargetScopeStatus.NEEDS_REVIEW;

import com.careeros.application.JobAdmissionPorts.JobAdmissions;
import java.util.Objects;

public final class JobLibrarySummaryService {
    private final JobAdmissions admissions;

    public JobLibrarySummaryService(JobAdmissions admissions) {
        this.admissions = Objects.requireNonNull(admissions);
    }

    public JobLibrarySummary load() {
        var summary = admissions.summarize();
        return new JobLibrarySummary(
            summary.total(),
            summary.count(RAW),
            summary.count(PARSED),
            summary.count(NORMALIZED),
            summary.count(REVIEW_REQUIRED),
            summary.count(VERIFIED),
            summary.count(REJECTED),
            summary.count(FAILED),
            summary.count(INCLUDED),
            summary.count(EXCLUDED),
            summary.count(NEEDS_REVIEW),
            summary.opportunityReady()
        );
    }

    public record JobLibrarySummary(
        long total,
        long raw,
        long parsed,
        long normalized,
        long reviewRequired,
        long verified,
        long rejected,
        long failed,
        long included,
        long excluded,
        long needsReview,
        long opportunityReady
    ) {}
}
