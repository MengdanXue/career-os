package com.careeros.application;

import com.careeros.domain.DomainEnums.DataQualityStatus;
import com.careeros.domain.DomainEnums.TargetScopeStatus;
import com.careeros.domain.JobAdmission;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.Collection;
import java.util.LinkedHashMap;

public final class JobAdmissionPorts {
    private JobAdmissionPorts() {}

    public interface JobAdmissions {
        Optional<JobAdmission> findByJobId(UUID jobId);

        default Map<UUID, JobAdmission> findByJobIds(Collection<UUID> jobIds) {
            var result = new LinkedHashMap<UUID, JobAdmission>();
            jobIds.forEach(jobId -> findByJobId(jobId).ifPresent(value -> result.put(jobId, value)));
            return Map.copyOf(result);
        }

        default Optional<JobAdmission> findByJobIdForUpdate(UUID jobId) { return findByJobId(jobId); }

        JobAdmission save(JobAdmission value);

        default List<JobAdmission> saveAll(Collection<JobAdmission> values) {
            return values.stream().map(this::save).toList();
        }

        default List<JobAdmission> findCandidateMatches() { return List.of(); }

        AdmissionSummary summarize();
    }

    public interface JobFieldEvidence {
        FieldEvidenceCoverage coverage(UUID jobId);

        default Map<UUID, FieldEvidenceCoverage> coverage(Collection<UUID> jobIds) {
            var result = new LinkedHashMap<UUID, FieldEvidenceCoverage>();
            jobIds.forEach(jobId -> result.put(jobId, coverage(jobId)));
            return Map.copyOf(result);
        }
    }

    public record EvidenceReference(
        String fieldName,
        String factStatus,
        String sourceTitle,
        String sourceUrl,
        String locator,
        String excerpt
    ) {}

    public record FieldEvidenceCoverage(
        Set<String> explicitFields,
        Set<String> conflictFields,
        boolean officialAttachment,
        boolean applicationDeadlineExplicit,
        boolean employmentIdentityExplicit,
        Set<String> notRequiredFields,
        List<EvidenceReference> evidenceReferences
    ) {
        public FieldEvidenceCoverage(
            Set<String> explicitFields,
            Set<String> conflictFields,
            boolean officialAttachment,
            boolean applicationDeadlineExplicit,
            boolean employmentIdentityExplicit
        ) {
            this(explicitFields, conflictFields, officialAttachment,
                applicationDeadlineExplicit, employmentIdentityExplicit, Set.of(), List.of());
        }

        public FieldEvidenceCoverage(
            Set<String> explicitFields,
            Set<String> conflictFields,
            boolean officialAttachment,
            boolean applicationDeadlineExplicit,
            boolean employmentIdentityExplicit,
            List<EvidenceReference> evidenceReferences
        ) {
            this(explicitFields, conflictFields, officialAttachment,
                applicationDeadlineExplicit, employmentIdentityExplicit, Set.of(), evidenceReferences);
        }

        public FieldEvidenceCoverage {
            explicitFields = explicitFields == null ? Set.of() : Set.copyOf(explicitFields);
            conflictFields = conflictFields == null ? Set.of() : Set.copyOf(conflictFields);
            notRequiredFields = notRequiredFields == null ? Set.of() : Set.copyOf(notRequiredFields);
            evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
        }

        public boolean explicit(String field) {
            return (explicitFields.contains(field) || notRequiredFields.contains(field))
                && !conflictFields.contains(field);
        }

        public boolean notRequired(String field) {
            return notRequiredFields.contains(field) && !conflictFields.contains(field);
        }
    }

    public record AdmissionSummary(
        long total,
        Map<DataQualityStatus, Long> byQuality,
        Map<TargetScopeStatus, Long> byTargetScope,
        long opportunityReady
    ) {
        public AdmissionSummary {
            if (total < 0 || opportunityReady < 0) {
                throw new IllegalArgumentException("summary counts must not be negative");
            }
            byQuality = byQuality == null ? Map.of() : Map.copyOf(byQuality);
            byTargetScope = byTargetScope == null ? Map.of() : Map.copyOf(byTargetScope);
        }

        public long count(DataQualityStatus status) {
            return byQuality.getOrDefault(status, 0L);
        }

        public long count(TargetScopeStatus status) {
            return byTargetScope.getOrDefault(status, 0L);
        }
    }
}
