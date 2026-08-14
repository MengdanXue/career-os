package com.careeros.infrastructure.extraction;

import com.careeros.domain.DomainEnums.ReviewStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewItemJpaRepository
    extends JpaRepository<ExtractionJpaModels.ReviewItemEntity, UUID> {
    Optional<ExtractionJpaModels.ReviewItemEntity> findByExtractionRunId(UUID extractionRunId);
    Page<ExtractionJpaModels.ReviewItemEntity> findByStatusOrderByCreatedAtAsc(
        ReviewStatus status, Pageable pageable);
}

interface ReviewIssueJpaRepository
    extends JpaRepository<ExtractionJpaModels.ReviewIssueEntity, UUID> {
    List<ExtractionJpaModels.ReviewIssueEntity> findByReviewItemIdOrderByIdAsc(UUID reviewItemId);
}

interface ReviewActionJpaRepository
    extends JpaRepository<ExtractionJpaModels.ReviewActionEntity, UUID> {
    List<ExtractionJpaModels.ReviewActionEntity> findByReviewItemIdOrderByActedAtAsc(UUID reviewItemId);
}
