package com.careeros.infrastructure.acquisition;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface RecruitmentSourceJpaRepository
    extends JpaRepository<AcquisitionJpaModels.RecruitmentSourceEntity, UUID> {
    List<AcquisitionJpaModels.RecruitmentSourceEntity>
        findByEnabledTrueAndNextDueAtLessThanEqualOrderByNextDueAtAscIdAsc(Instant now, Pageable pageable);
}
