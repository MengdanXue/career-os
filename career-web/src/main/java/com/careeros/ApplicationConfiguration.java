package com.careeros;

import com.careeros.application.*;
import com.careeros.domain.EligibilityEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ApplicationConfiguration {
    @Bean EligibilityEvaluator eligibilityEvaluator() { return new EligibilityEvaluator(); }
    @Bean CareerDecisionService careerDecisionService(RepositoryPorts.CandidateProfiles candidates, RepositoryPorts.JobPostings jobs, RepositoryPorts.EligibilityAssessments assessments, RepositoryPorts.Opportunities opportunities, EligibilityEvaluator evaluator) { return new CareerDecisionService(candidates,jobs,assessments,opportunities,evaluator); }
}
