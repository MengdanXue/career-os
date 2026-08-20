package com.careeros.infrastructure.persistence;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AssessmentDimensionJpaRepository extends JpaRepository<DecisionJpaModels.AssessmentDimensionEntity,UUID> { List<DecisionJpaModels.AssessmentDimensionEntity> findByDecisionAssessmentId(UUID decisionId); }
