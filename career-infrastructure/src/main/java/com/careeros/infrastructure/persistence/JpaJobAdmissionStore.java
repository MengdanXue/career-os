package com.careeros.infrastructure.persistence;

import static com.careeros.application.JobAdmissionPorts.AdmissionSummary;
import static com.careeros.application.JobAdmissionPorts.JobAdmissions;
import static com.careeros.domain.DomainEnums.DataQualityStatus.VERIFIED;
import static com.careeros.domain.DomainEnums.TargetScopeStatus.INCLUDED;
import static com.careeros.domain.DomainEnums.EmploymentType.UNKNOWN;

import com.careeros.domain.DomainEnums.DataQualityStatus;
import com.careeros.domain.DomainEnums.TargetScopeStatus;
import com.careeros.domain.JobAdmission;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly = true)
public class JpaJobAdmissionStore implements JobAdmissions {
    private final JobAdmissionJpaRepository repository;

    public JpaJobAdmissionStore(JobAdmissionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<JobAdmission> findByJobId(UUID jobId) {
        return repository.findById(jobId).map(JpaJobAdmissionStore::toDomain);
    }

    @Override
    public java.util.Map<UUID, JobAdmission> findByJobIds(java.util.Collection<UUID> jobIds) {
        var result = new java.util.LinkedHashMap<UUID, JobAdmission>();
        repository.findAllById(jobIds).forEach(value -> result.put(value.jobPostingId, toDomain(value)));
        return java.util.Map.copyOf(result);
    }

    @Override
    public Optional<JobAdmission> findByJobIdForUpdate(UUID jobId) {
        return repository.findByIdForDecision(jobId).map(JpaJobAdmissionStore::toDomain);
    }

    @Override
    @Transactional
    public JobAdmission save(JobAdmission value) {
        return toDomain(repository.save(toEntity(value)));
    }

    @Override
    @Transactional
    public java.util.List<JobAdmission> saveAll(java.util.Collection<JobAdmission> values) {
        return repository.saveAll(values.stream().map(JpaJobAdmissionStore::toEntity).toList())
            .stream().map(JpaJobAdmissionStore::toDomain).toList();
    }

    @Override
    public java.util.List<JobAdmission> findCandidateMatches() {
        return repository.findByDataQualityStatusInAndTargetScopeStatusNot(
            java.util.List.of(DataQualityStatus.VERIFIED, DataQualityStatus.NORMALIZED,
                DataQualityStatus.REVIEW_REQUIRED),
            TargetScopeStatus.EXCLUDED).stream()
            .map(JpaJobAdmissionStore::toDomain)
            .filter(value -> value.reasonCodes().contains(
                com.careeros.domain.DomainEnums.JobAdmissionReason.TARGET_TECHNICAL_ROLE))
            .toList();
    }

    @Override
    public AdmissionSummary summarize() {
        var byQuality = new EnumMap<DataQualityStatus, Long>(DataQualityStatus.class);
        repository.countByQuality().forEach(row ->
            byQuality.put((DataQualityStatus) row[0], (Long) row[1]));
        var byTargetScope = new EnumMap<TargetScopeStatus, Long>(TargetScopeStatus.class);
        repository.countByTargetScope().forEach(row ->
            byTargetScope.put((TargetScopeStatus) row[0], (Long) row[1]));
        return new AdmissionSummary(
            repository.count(),
            byQuality,
            byTargetScope,
            repository.countDecisionReady(VERIFIED, INCLUDED, UNKNOWN)
        );
    }

    private static JpaModels.JobAdmissionEntity toEntity(JobAdmission value) {
        var entity = new JpaModels.JobAdmissionEntity();
        entity.jobPostingId = value.jobPostingId();
        entity.dataQualityStatus = value.dataQualityStatus();
        entity.targetScopeStatus = value.targetScopeStatus();
        entity.reasonCodes = new LinkedHashSet<>(value.reasonCodes());
        entity.evaluatorVersion = value.evaluatorVersion();
        entity.assessedAt = value.assessedAt();
        entity.humanVerified = value.humanVerified();
        return entity;
    }

    private static JobAdmission toDomain(JpaModels.JobAdmissionEntity value) {
        return new JobAdmission(
            value.jobPostingId,
            value.dataQualityStatus,
            value.targetScopeStatus,
            value.reasonCodes,
            value.evaluatorVersion,
            value.assessedAt,
            value.humanVerified
        );
    }
}
