package com.careeros.infrastructure.acquisition;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SourceCrawlRunJpaRepository
    extends JpaRepository<AcquisitionJpaModels.SourceCrawlRunEntity, UUID> {}
