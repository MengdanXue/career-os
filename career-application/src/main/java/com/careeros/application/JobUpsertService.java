package com.careeros.application;

import com.careeros.domain.DomainEnums.EducationLevel;
import com.careeros.domain.DomainEnums.EmploymentType;
import com.careeros.domain.DomainEnums.JobFamily;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public interface JobUpsertService {
    JobUpsertResult upsert(JobUpsertBatch batch);
    String stableKey(NormalizedJob job);
    String contentFingerprint(NormalizedJob job);

    record NormalizedJob(
        UUID recruitmentEventId,
        UUID organizationId,
        String organizationName,
        String externalJobCode,
        String title,
        JobFamily jobFamily,
        EmploymentType employmentType,
        String location,
        int headcount,
        EducationLevel minimumEducation,
        Set<String> exactMajors,
        Set<Integer> acceptedGraduationYears,
        Integer maximumAge,
        LocalDate ageReferenceDate,
        Integer minimumExperienceYears,
        Set<String> requiredProfessionalTitles,
        String duties,
        String sourceUrl,
        String stableSourceUrl,
        String legacyStableSourceUrl,
        List<UUID> evidenceIds
    ) {
        public NormalizedJob(
            UUID recruitmentEventId,
            UUID organizationId,
            String organizationName,
            String externalJobCode,
            String title,
            JobFamily jobFamily,
            EmploymentType employmentType,
            String location,
            int headcount,
            EducationLevel minimumEducation,
            Set<String> exactMajors,
            Set<Integer> acceptedGraduationYears,
            Integer maximumAge,
            LocalDate ageReferenceDate,
            Integer minimumExperienceYears,
            Set<String> requiredProfessionalTitles,
            String duties,
            String sourceUrl,
            List<UUID> evidenceIds
        ) {
            this(
                recruitmentEventId, organizationId, organizationName, externalJobCode, title,
                jobFamily, employmentType, location, headcount, minimumEducation, exactMajors,
                acceptedGraduationYears, maximumAge, ageReferenceDate, minimumExperienceYears,
                requiredProfessionalTitles, duties, sourceUrl, sourceUrl, null, evidenceIds);
        }

        public NormalizedJob(
            UUID recruitmentEventId,
            UUID organizationId,
            String organizationName,
            String externalJobCode,
            String title,
            JobFamily jobFamily,
            EmploymentType employmentType,
            String location,
            int headcount,
            EducationLevel minimumEducation,
            Set<String> exactMajors,
            Set<Integer> acceptedGraduationYears,
            Integer maximumAge,
            LocalDate ageReferenceDate,
            Integer minimumExperienceYears,
            Set<String> requiredProfessionalTitles,
            String duties,
            String sourceUrl,
            String stableSourceUrl,
            List<UUID> evidenceIds
        ) {
            this(
                recruitmentEventId, organizationId, organizationName, externalJobCode, title,
                jobFamily, employmentType, location, headcount, minimumEducation, exactMajors,
                acceptedGraduationYears, maximumAge, ageReferenceDate, minimumExperienceYears,
                requiredProfessionalTitles, duties, sourceUrl, stableSourceUrl, null, evidenceIds);
        }

        public NormalizedJob {
            Objects.requireNonNull(recruitmentEventId, "recruitmentEventId");
            Objects.requireNonNull(organizationId, "organizationId");
            requireText(organizationName, "organizationName");
            requireText(title, "title");
            Objects.requireNonNull(jobFamily, "jobFamily");
            Objects.requireNonNull(employmentType, "employmentType");
            if (headcount < 1) throw new IllegalArgumentException("headcount must be positive");
            Objects.requireNonNull(minimumEducation, "minimumEducation");
            exactMajors = exactMajors == null ? Set.of() : Set.copyOf(exactMajors);
            acceptedGraduationYears = acceptedGraduationYears == null ? Set.of() : Set.copyOf(acceptedGraduationYears);
            requiredProfessionalTitles = requiredProfessionalTitles == null ? Set.of() : Set.copyOf(requiredProfessionalTitles);
            requireText(sourceUrl, "sourceUrl");
            requireText(stableSourceUrl, "stableSourceUrl");
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    record JobUpsertBatch(
        UUID recruitmentEventId,
        String sourceUrl,
        List<NormalizedJob> jobs,
        boolean completeSnapshot,
        List<String> validationErrors,
        UUID legacyRecruitmentEventId
    ) {
        public JobUpsertBatch(
            UUID recruitmentEventId,
            String sourceUrl,
            List<NormalizedJob> jobs,
            boolean completeSnapshot,
            List<String> validationErrors
        ) {
            this(recruitmentEventId, sourceUrl, jobs, completeSnapshot, validationErrors, null);
        }

        public JobUpsertBatch {
            Objects.requireNonNull(recruitmentEventId, "recruitmentEventId");
            if (recruitmentEventId.equals(legacyRecruitmentEventId)) {
                throw new IllegalArgumentException("legacy recruitment event must differ from the current event");
            }
            requireText(sourceUrl, "sourceUrl");
            jobs = jobs == null ? List.of() : List.copyOf(jobs);
            validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
        }
    }

    record JobUpsertResult(
        int inserted,
        int updated,
        int unchanged,
        int deactivated,
        List<UUID> jobIds
    ) {
        public JobUpsertResult {
            if (inserted < 0 || updated < 0 || unchanged < 0 || deactivated < 0) {
                throw new IllegalArgumentException("upsert counters must not be negative");
            }
            jobIds = jobIds == null ? List.of() : List.copyOf(jobIds);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
