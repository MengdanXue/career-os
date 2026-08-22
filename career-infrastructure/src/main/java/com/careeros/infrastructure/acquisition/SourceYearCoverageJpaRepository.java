package com.careeros.infrastructure.acquisition;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SourceYearCoverageJpaRepository extends JpaRepository<
    AcquisitionJpaModels.SourceYearCoverageEntity,
    AcquisitionJpaModels.SourceYearCoverageId
> {
    List<AcquisitionJpaModels.SourceYearCoverageEntity> findByIdSourceIdOrderByIdRecruitmentYearAsc(UUID sourceId);
    List<AcquisitionJpaModels.SourceYearCoverageEntity> findByIdRecruitmentYearOrderByIdSourceIdAsc(int recruitmentYear);
    List<AcquisitionJpaModels.SourceYearCoverageEntity> findByIdSourceIdAndIdRecruitmentYear(UUID sourceId, int recruitmentYear);
}
