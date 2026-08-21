package com.careeros.domain;

import com.careeros.domain.DomainEnums.EducationLevel;
import com.careeros.domain.DomainEnums.EmploymentType;
import com.careeros.domain.DomainEnums.JobFamily;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record JobPosting(
    UUID id, UUID recruitmentEventId, UUID organizationId, String externalJobCode,
    String title, JobFamily jobFamily, EmploymentType employmentType, String location,
    int headcount, EducationLevel minimumEducation, Set<String> exactMajors,
    Set<Integer> acceptedGraduationYears, Integer maximumAge, LocalDate ageReferenceDate,
    Integer minimumExperienceYears, Set<String> requiredProfessionalTitles, String duties,
    String sourceUrl, List<UUID> evidenceIds,
    String supervisingDepartment, String jobCategory, String jobGrade,
    String educationRequirementText, String degreeRequirement, String majorRequirementText,
    String ageRequirementText, String genderRequirement, String candidateScope,
    String otherRequirements, String originalRequirementText, String interviewRatio,
    Boolean professionalTestRequired, String contactPhone
) {
    public JobPosting {
        Objects.requireNonNull(id);
        Objects.requireNonNull(recruitmentEventId);
        Objects.requireNonNull(organizationId);
        require(title, "title");
        Objects.requireNonNull(jobFamily);
        Objects.requireNonNull(employmentType);
        Objects.requireNonNull(minimumEducation);
        require(sourceUrl, "sourceUrl");
        if (headcount < 1) throw new IllegalArgumentException("headcount must be positive");
        if (maximumAge != null && maximumAge < 16) throw new IllegalArgumentException("maximumAge is invalid");
        if (minimumExperienceYears != null && minimumExperienceYears < 0) {
            throw new IllegalArgumentException("minimumExperienceYears is invalid");
        }
        exactMajors = exactMajors == null ? Set.of() : Set.copyOf(exactMajors);
        acceptedGraduationYears = acceptedGraduationYears == null ? Set.of() : Set.copyOf(acceptedGraduationYears);
        requiredProfessionalTitles = requiredProfessionalTitles == null ? Set.of() : Set.copyOf(requiredProfessionalTitles);
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }

    public JobPosting(
        UUID id, UUID recruitmentEventId, UUID organizationId, String externalJobCode,
        String title, JobFamily jobFamily, EmploymentType employmentType, String location,
        int headcount, EducationLevel minimumEducation, Set<String> exactMajors,
        Set<Integer> acceptedGraduationYears, Integer maximumAge, LocalDate ageReferenceDate,
        Integer minimumExperienceYears, Set<String> requiredProfessionalTitles, String duties,
        String sourceUrl, List<UUID> evidenceIds
    ) {
        this(id, recruitmentEventId, organizationId, externalJobCode, title, jobFamily,
            employmentType, location, headcount, minimumEducation, exactMajors,
            acceptedGraduationYears, maximumAge, ageReferenceDate, minimumExperienceYears,
            requiredProfessionalTitles, duties, sourceUrl, evidenceIds,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
