package com.careeros.infrastructure.persistence;

import com.careeros.domain.DomainEnums.DataQualityStatus;
import com.careeros.domain.DomainEnums.TargetScopeStatus;
import com.careeros.domain.DomainEnums.EmploymentType;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobAdmissionJpaRepository
    extends JpaRepository<JpaModels.JobAdmissionEntity, UUID> {

    @Query("select a.dataQualityStatus, count(a) from JpaModels$JobAdmissionEntity a group by a.dataQualityStatus")
    List<Object[]> countByQuality();

    @Query("select a.targetScopeStatus, count(a) from JpaModels$JobAdmissionEntity a group by a.targetScopeStatus")
    List<Object[]> countByTargetScope();

    long countByDataQualityStatusAndTargetScopeStatus(
        DataQualityStatus dataQualityStatus,
        TargetScopeStatus targetScopeStatus
    );

    @Query("select count(a) from JpaModels$JobAdmissionEntity a, JpaModels$JobPostingEntity j "
        + "where a.jobPostingId = j.id and a.dataQualityStatus = :quality "
        + "and a.targetScopeStatus = :scope and j.employmentType <> :unknown")
    long countDecisionReady(@Param("quality") DataQualityStatus quality, @Param("scope") TargetScopeStatus scope,
        @Param("unknown") EmploymentType unknown);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from JpaModels$JobAdmissionEntity a where a.jobPostingId = :id")
    Optional<JpaModels.JobAdmissionEntity> findByIdForDecision(@Param("id") UUID id);

    List<JpaModels.JobAdmissionEntity> findByDataQualityStatusAndTargetScopeStatusNot(
        DataQualityStatus dataQualityStatus, TargetScopeStatus targetScopeStatus);

    List<JpaModels.JobAdmissionEntity> findByDataQualityStatusInAndTargetScopeStatusNot(
        Collection<DataQualityStatus> dataQualityStatuses, TargetScopeStatus targetScopeStatus);

    boolean existsByJobPostingIdAndHumanVerifiedTrue(UUID jobPostingId);
}
