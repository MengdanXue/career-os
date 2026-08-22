package com.careeros.domain;

import com.careeros.domain.DomainEnums.EducationLevel;
import java.util.Objects;

public record EducationRecord(
    String institutionName,
    String countryOrRegion,
    EducationLevel educationLevel,
    String majorName,
    Integer graduationYear,
    Integer graduationMonth,
    CompletionStatus completionStatus,
    CredentialVerificationStatus credentialVerificationStatus
) {
    public enum CompletionStatus { COMPLETED, EXPECTED }
    public enum CredentialVerificationStatus { NOT_REQUIRED, PLANNED, IN_PROGRESS, VERIFIED, UNKNOWN }

    public EducationRecord {
        institutionName = optional(institutionName);
        countryOrRegion = optional(countryOrRegion);
        Objects.requireNonNull(educationLevel, "educationLevel");
        if (educationLevel == EducationLevel.UNKNOWN) {
            throw new IllegalArgumentException("educationLevel cannot be UNKNOWN");
        }
        majorName = required(majorName, "majorName");
        if (graduationYear != null && (graduationYear < 1900 || graduationYear > 2100)) {
            throw new IllegalArgumentException("graduationYear is invalid");
        }
        if (graduationMonth != null && (graduationMonth < 1 || graduationMonth > 12)) {
            throw new IllegalArgumentException("graduationMonth is invalid");
        }
        Objects.requireNonNull(completionStatus, "completionStatus");
        Objects.requireNonNull(credentialVerificationStatus, "credentialVerificationStatus");
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
