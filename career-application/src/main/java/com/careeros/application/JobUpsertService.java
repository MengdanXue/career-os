package com.careeros.application;

import com.careeros.domain.DomainEnums.EducationLevel;
import com.careeros.domain.DomainEnums.EmploymentType;
import com.careeros.domain.DomainEnums.JobFamily;
import com.careeros.domain.DomainEnums.JobChangeKind;
import com.careeros.domain.DomainEnums.JobField;
import java.util.Map;
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
        List<UUID> evidenceIds,
        Map<JobField, List<UUID>> fieldEvidence
    ) {
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
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            fieldEvidence = fieldEvidence == null ? Map.of() : Map.copyOf(fieldEvidence);
        }
    }

    record JobUpsertBatch(
        UUID recruitmentEventId,
        String sourceUrl,
        List<NormalizedJob> jobs,
        boolean completeSnapshot,
        List<String> validationErrors
    ) {
        public JobUpsertBatch {
            Objects.requireNonNull(recruitmentEventId, "recruitmentEventId");
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
        List<UUID> jobIds,
        List<JobChange> changes
    ) {
        public JobUpsertResult {
            if (inserted < 0 || updated < 0 || unchanged < 0 || deactivated < 0) {
                throw new IllegalArgumentException("upsert counters must not be negative");
            }
            jobIds = jobIds == null ? List.of() : List.copyOf(jobIds);
            changes = changes == null ? List.of() : List.copyOf(changes);
        }

        public JobUpsertResult(int inserted, int updated, int unchanged, int deactivated, List<UUID> jobIds) {
            this(inserted, updated, unchanged, deactivated, jobIds, List.of());
        }
    }

    /**
     * 单个岗位在本次入库中的变化。计数器只能回答"变了多少条"，每日摘要要回答"哪几条变了"，
     * 因此逐岗保留。UNCHANGED 也在列——摘要负责把它滤掉，而不是让它在这里就消失。
     */
    record JobChange(UUID jobPostingId, JobChangeKind kind) {
        public JobChange {
            Objects.requireNonNull(jobPostingId, "jobPostingId");
            Objects.requireNonNull(kind, "kind");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
