package com.careeros.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

public record CandidateEmploymentRecord(
    String employerName,
    String roleTitle,
    LocalDate startsOn,
    LocalDate endsOn,
    EmploymentMode employmentMode,
    VerificationStatus verificationStatus,
    Set<String> evidenceTypes
) {
    public enum EmploymentMode { FULL_TIME, PART_TIME, INTERNSHIP, UNKNOWN }
    public enum VerificationStatus { UNVERIFIED, PARTIAL, VERIFIED, REJECTED }

    public CandidateEmploymentRecord {
        require(employerName, "employerName");
        require(roleTitle, "roleTitle");
        Objects.requireNonNull(startsOn, "startsOn is required");
        Objects.requireNonNull(employmentMode, "employmentMode is required");
        Objects.requireNonNull(verificationStatus, "verificationStatus is required");
        if (endsOn != null && endsOn.isBefore(startsOn)) {
            throw new IllegalArgumentException("endsOn cannot precede startsOn");
        }
        evidenceTypes = evidenceTypes == null ? Set.of() : Set.copyOf(evidenceTypes);
        if (evidenceTypes.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("evidenceTypes cannot contain blank values");
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
