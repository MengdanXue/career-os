package com.careeros.infrastructure.persistence;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RecruitmentEventJpaRepository extends JpaRepository<JpaModels.RecruitmentEventEntity, UUID> { Optional<JpaModels.RecruitmentEventEntity> findFirstBySourceUrl(String sourceUrl); }
