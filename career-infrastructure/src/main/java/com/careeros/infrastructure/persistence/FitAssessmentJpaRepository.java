package com.careeros.infrastructure.persistence;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FitAssessmentJpaRepository extends JpaRepository<DecisionJpaModels.FitAssessmentEntity,UUID> {}
