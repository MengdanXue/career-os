package com.careeros.infrastructure.acquisition;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AcquisitionChangeJpaRepository
    extends JpaRepository<AcquisitionJpaModels.AcquisitionChangeEntity, UUID> {}
