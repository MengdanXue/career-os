package com.careeros.infrastructure.acquisition;

import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface SourceCrawlRunJpaRepository
    extends JpaRepository<AcquisitionJpaModels.SourceCrawlRunEntity, UUID> {
    Optional<AcquisitionJpaModels.SourceCrawlRunEntity> findTopBySourceIdOrderByStartedAtDescIdDesc(UUID sourceId);
}
