package com.careeros.domain;

import com.careeros.domain.DomainEnums.EducationLevel;
import com.careeros.domain.DomainEnums.EmploymentType;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record CandidateProfile(UUID id, String displayName, PartialDate birthDate, EducationLevel highestEducation, Set<String> majors, Integer graduationYear, Integer experienceYears, Set<String> professionalTitles, List<String> preferredLocations, Set<EmploymentType> acceptedEmploymentTypes, String profileVersion) {
    public CandidateProfile {
        Objects.requireNonNull(id); require(displayName, "displayName"); Objects.requireNonNull(birthDate); Objects.requireNonNull(highestEducation);
        if (experienceYears != null && experienceYears < 0) throw new IllegalArgumentException("experienceYears is invalid");
        majors = majors == null ? Set.of() : Set.copyOf(majors); professionalTitles = professionalTitles == null ? Set.of() : Set.copyOf(professionalTitles);
        preferredLocations = preferredLocations == null ? List.of() : List.copyOf(preferredLocations); acceptedEmploymentTypes = acceptedEmploymentTypes == null ? Set.of() : Set.copyOf(acceptedEmploymentTypes);
        require(profileVersion, "profileVersion");
    }
    private static void require(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required"); }
}
