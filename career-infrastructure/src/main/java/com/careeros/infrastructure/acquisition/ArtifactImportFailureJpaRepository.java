package com.careeros.infrastructure.acquisition;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ArtifactImportFailureJpaRepository extends JpaRepository<
    AcquisitionJpaModels.ArtifactImportFailureEntity, UUID
> {
    List<AcquisitionJpaModels.ArtifactImportFailureEntity> findBySourceIdAndRunIdOrderByOccurredAtAscIdAsc(
        UUID sourceId, UUID runId);
    List<AcquisitionJpaModels.ArtifactImportFailureEntity> findBySourceIdOrderByOccurredAtDescIdDesc(UUID sourceId);
    long countBySourceId(UUID sourceId);
}
