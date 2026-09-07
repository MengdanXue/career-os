package com.careeros.infrastructure.acquisition;

import com.careeros.domain.acquisition.ArtifactDiscovery.DiscoveryStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ArtifactDiscoveryJpaRepository extends JpaRepository<
    AcquisitionJpaModels.ArtifactDiscoveryEntity, UUID
> {
    Optional<AcquisitionJpaModels.ArtifactDiscoveryEntity> findBySourceIdAndCanonicalUri(
        UUID sourceId, String canonicalUri);
    List<AcquisitionJpaModels.ArtifactDiscoveryEntity> findBySourceIdAndRunIdOrderByLastAttemptAtAsc(
        UUID sourceId, UUID runId);
    long countBySourceIdAndStatusIn(UUID sourceId, List<DiscoveryStatus> statuses);
}
