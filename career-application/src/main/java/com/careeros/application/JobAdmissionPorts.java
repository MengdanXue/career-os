package com.careeros.application;

import com.careeros.domain.DomainEnums.DataQualityStatus;
import com.careeros.domain.DomainEnums.TargetScopeStatus;
import com.careeros.domain.JobAdmission;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
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
        String excerpt,
        UUID evidenceFragmentId
    ) {
        public EvidenceReference(String fieldName, String factStatus, String sourceTitle,
                                 String sourceUrl, String locator, String excerpt) {
            this(fieldName, factStatus, sourceTitle, sourceUrl, locator, excerpt, null);
        }
    }

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

        /**
         * 官方字段名 → 支撑该字段的证据片段 ID。产品需求 §10.6 要求每个资格结论都有可定位
         * 证据，资格评估器据此给逐条规则挂片段而不是只挂公告级证据。
         * 事实状态为 UNKNOWN 的行没有片段（表上的 CHECK 保证了这一点），自然不会进来。
         */
        public Map<String, List<UUID>> evidenceFragmentsByField() {
            var byField = new LinkedHashMap<String, List<UUID>>();
            for (EvidenceReference reference : evidenceReferences) {
                if (reference.evidenceFragmentId() == null) continue;
                byField.computeIfAbsent(reference.fieldName(), key -> new ArrayList<>())
                    .add(reference.evidenceFragmentId());
            }
            byField.replaceAll((field, ids) -> List.copyOf(ids));
            return Map.copyOf(byField);
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
