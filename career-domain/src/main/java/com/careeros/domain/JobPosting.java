package com.careeros.domain;

import com.careeros.domain.DomainEnums.EducationLevel;
import com.careeros.domain.DomainEnums.EmploymentType;
import com.careeros.domain.DomainEnums.JobFamily;
import com.careeros.domain.DomainEnums.JobField;
import java.util.Map;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record JobPosting(UUID id, UUID recruitmentEventId, UUID organizationId, String externalJobCode, String title, JobFamily jobFamily, EmploymentType employmentType, String location, int headcount, EducationLevel minimumEducation, Set<String> exactMajors, Set<Integer> acceptedGraduationYears, Integer maximumAge, LocalDate ageReferenceDate, Integer minimumExperienceYears, Set<String> requiredProfessionalTitles, String duties, String sourceUrl, List<UUID> evidenceIds, Map<JobField, List<UUID>> fieldEvidence) {
    public JobPosting {
        Objects.requireNonNull(id); Objects.requireNonNull(recruitmentEventId); Objects.requireNonNull(organizationId); require(title, "title");
        Objects.requireNonNull(jobFamily); Objects.requireNonNull(employmentType); Objects.requireNonNull(minimumEducation); require(sourceUrl, "sourceUrl");
        if (headcount < 1) throw new IllegalArgumentException("headcount must be positive");
        if (maximumAge != null && maximumAge < 16) throw new IllegalArgumentException("maximumAge is invalid");
        if (minimumExperienceYears != null && minimumExperienceYears < 0) throw new IllegalArgumentException("minimumExperienceYears is invalid");
        exactMajors = exactMajors == null ? Set.of() : Set.copyOf(exactMajors);
        acceptedGraduationYears = acceptedGraduationYears == null ? Set.of() : Set.copyOf(acceptedGraduationYears);
        requiredProfessionalTitles = requiredProfessionalTitles == null ? Set.of() : Set.copyOf(requiredProfessionalTitles);
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        fieldEvidence = fieldEvidence == null ? Map.of() : Map.copyOf(fieldEvidence);
    }

    /**
     * 某个硬条件字段的证据片段。抽取阶段 {@link ExtractedFact} 记下的片段 ID 会一路带到这里，
     * 让资格结论能指向公告里的具体一句话；没有片段级证据时回落到公告级 {@code evidenceIds}。
     */
    public List<UUID> evidenceFor(JobField field) {
        List<UUID> fragments = fieldEvidence.get(field);
        return fragments == null || fragments.isEmpty() ? evidenceIds : fragments;
    }
    private static void require(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required"); }
}
