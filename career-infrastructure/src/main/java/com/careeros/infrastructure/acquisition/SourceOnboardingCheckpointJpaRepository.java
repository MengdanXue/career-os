package com.careeros.infrastructure.acquisition;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SourceOnboardingCheckpointJpaRepository extends JpaRepository<
    AcquisitionJpaModels.SourceOnboardingCheckpointEntity,
    AcquisitionJpaModels.SourceOnboardingCheckpointId
> {
    List<AcquisitionJpaModels.SourceOnboardingCheckpointEntity> findByIdSourceIdOrderByIdCheckpointAsc(UUID sourceId);
}
