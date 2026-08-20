package com.careeros.domain;

import com.careeros.domain.DomainEnums.EducationLevel;
import com.careeros.domain.DomainEnums.EmploymentType;
import com.careeros.domain.DomainEnums.JobFamily;
import com.careeros.domain.DomainEnums.OrganizationType;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record CandidateProfile(UUID id, String displayName, PartialDate birthDate, EducationLevel highestEducation, Set<String> majors, Integer graduationYear, Integer experienceYears, Set<String> professionalTitles, List<String> preferredLocations, Set<EmploymentType> acceptedEmploymentTypes, String profileVersion, Set<String> skills, Set<String> researchKeywords, Set<JobFamily> targetJobFamilies, Set<OrganizationType> preferredOrganizationTypes) {
    public CandidateProfile {
        Objects.requireNonNull(id); require(displayName, "displayName"); Objects.requireNonNull(birthDate); Objects.requireNonNull(highestEducation);
        if (experienceYears != null && experienceYears < 0) throw new IllegalArgumentException("experienceYears is invalid");
        majors = majors == null ? Set.of() : Set.copyOf(majors); professionalTitles = professionalTitles == null ? Set.of() : Set.copyOf(professionalTitles);
        preferredLocations = preferredLocations == null ? List.of() : List.copyOf(preferredLocations); acceptedEmploymentTypes = acceptedEmploymentTypes == null ? Set.of() : Set.copyOf(acceptedEmploymentTypes);
        require(profileVersion, "profileVersion");
        skills = skills == null ? Set.of() : Set.copyOf(skills);
        researchKeywords = researchKeywords == null ? Set.of() : Set.copyOf(researchKeywords);
        targetJobFamilies = targetJobFamilies == null ? Set.of() : Set.copyOf(targetJobFamilies);
        preferredOrganizationTypes = preferredOrganizationTypes == null ? Set.of() : Set.copyOf(preferredOrganizationTypes);
    }
    public CandidateProfile(UUID id, String displayName, PartialDate birthDate, EducationLevel highestEducation, Set<String> majors, Integer graduationYear, Integer experienceYears, Set<String> professionalTitles, List<String> preferredLocations, Set<EmploymentType> acceptedEmploymentTypes, String profileVersion) {
        this(id, displayName, birthDate, highestEducation, majors, graduationYear, experienceYears,
            professionalTitles, preferredLocations, acceptedEmploymentTypes, profileVersion,
            Set.of(), Set.of(), Set.of(), Set.of());
    }
    private static void require(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required"); }
}
