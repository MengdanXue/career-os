package com.careeros.infrastructure.acquisition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AcquiredDocumentJpaRepository
    extends JpaRepository<AcquisitionJpaModels.AcquiredDocumentEntity, UUID> {
    Optional<AcquisitionJpaModels.AcquiredDocumentEntity> findBySourceIdAndCanonicalUri(UUID sourceId, String canonicalUri);
    List<AcquisitionJpaModels.AcquiredDocumentEntity> findBySourceIdOrderByCanonicalUriAsc(UUID sourceId);
}
