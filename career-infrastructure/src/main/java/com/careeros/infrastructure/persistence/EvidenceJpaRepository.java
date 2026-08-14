package com.careeros.infrastructure.persistence;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface EvidenceJpaRepository extends JpaRepository<JpaModels.EvidenceEntity, UUID> {}
