package com.careeros.infrastructure.extraction;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.careeros.domain.DomainEnums.DataQualityStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtractionRunJpaRepository
    extends JpaRepository<ExtractionJpaModels.ExtractionRunEntity, UUID> {
    Optional<ExtractionJpaModels.ExtractionRunEntity> findByInputFingerprint(String inputFingerprint);
    long countByStatus(DataQualityStatus status);
}

interface SourceArtifactJpaRepository
    extends JpaRepository<ExtractionJpaModels.SourceArtifactEntity, UUID> {}

interface EvidenceFragmentJpaRepository
    extends JpaRepository<ExtractionJpaModels.EvidenceFragmentEntity, UUID> {
    List<ExtractionJpaModels.EvidenceFragmentEntity> findByEvidenceIdOrderByCreatedAtAsc(UUID evidenceId);
}
