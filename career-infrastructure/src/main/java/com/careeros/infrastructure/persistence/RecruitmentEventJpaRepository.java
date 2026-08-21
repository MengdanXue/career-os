package com.careeros.infrastructure.persistence;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RecruitmentEventJpaRepository extends JpaRepository<JpaModels.RecruitmentEventEntity, UUID> {
    Optional<JpaModels.RecruitmentEventEntity> findFirstBySourceUrl(String sourceUrl);
    Optional<JpaModels.RecruitmentEventEntity> findFirstByWorkbookIdentity(String workbookIdentity);
    List<JpaModels.RecruitmentEventEntity> findBySourceUrlIn(Collection<String> sourceUrls);
}
