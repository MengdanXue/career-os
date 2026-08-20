package com.careeros.infrastructure.persistence;

import com.careeros.domain.DomainEnums.DataQualityStatus;
import com.careeros.domain.DomainEnums.TargetScopeStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
