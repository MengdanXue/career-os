package com.careeros;

import com.careeros.application.*;
import com.careeros.application.ExtractionPorts.*;
import com.careeros.domain.EligibilityEvaluator;
import com.careeros.domain.JobLineageBuilder;
import com.careeros.domain.OpportunityForecaster;
import com.careeros.domain.OpportunityScorer;
import com.careeros.domain.OpportunityTierClassifier;
import com.careeros.domain.ReviewPolicy;
import com.careeros.infrastructure.artifact.FileSystemArtifactStore;
import com.careeros.infrastructure.extraction.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ApplicationConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean EligibilityEvaluator eligibilityEvaluator() { return new EligibilityEvaluator(); }
    @Bean OpportunityTierClassifier opportunityTierClassifier() { return new OpportunityTierClassifier(); }
    @Bean OpportunityScorer opportunityScorer() { return new OpportunityScorer(); }
    @Bean JobLineageBuilder jobLineageBuilder() { return new JobLineageBuilder(); }
    @Bean OpportunityForecaster opportunityForecaster() { return new OpportunityForecaster(); }
    @Bean OpportunityHistoryService opportunityHistoryService(RepositoryPorts.JobPostings jobs, RepositoryPorts.RecruitmentEvents events, RepositoryPorts.Organizations organizations, JobLineageBuilder lineageBuilder, OpportunityForecaster forecaster) { return new OpportunityHistoryService(jobs,events,organizations,lineageBuilder,forecaster); }
    @Bean CareerDecisionService careerDecisionService(RepositoryPorts.CandidateProfiles candidates, RepositoryPorts.JobPostings jobs, RepositoryPorts.Organizations organizations, RepositoryPorts.EligibilityAssessments assessments, RepositoryPorts.Opportunities opportunities, EligibilityEvaluator evaluator, OpportunityTierClassifier tierClassifier, OpportunityScorer scorer, OpportunityHistoryService history) { return new CareerDecisionService(candidates,jobs,organizations,assessments,opportunities,evaluator,tierClassifier,scorer,history); }
    @Bean ArtifactStore artifactStore(@Value("${career-os.artifacts.root:${user.dir}/var/artifacts}") String root) { return new FileSystemArtifactStore(Path.of(root)); }
    @Bean DocumentParser documentParser() { return new MediaTypeDocumentParser(List.of(new JsoupDocumentParser(),new PdfBoxDocumentParser())); }
    @Bean DocumentEnrichmentPort documentEnrichment() { return new NoOpDocumentEnrichment(); }
    @Bean EvidenceVerifier evidenceVerifier() { return new DefaultEvidenceVerifier(); }
    @Bean ReviewPolicy reviewPolicy(@Value("${career-os.extraction.auto-accept-confidence:0.90}") double threshold) { return new ReviewPolicy(threshold); }
    @Bean ExtractionService extractionService(
        ArtifactStore artifacts,DocumentParser parser,DocumentEnrichmentPort enrichment,
        StructuredExtractor extractor,ProposalValidator validator,EvidenceVerifier verifier,
        ExtractionPersistence persistence,VerifiedProposalWriter writer,UnitOfWork unitOfWork,
        FingerprintLock fingerprintLock,ExtractionObserver observer,ReviewPolicy reviewPolicy,Clock clock,
        @Value("${career-os.extraction.max-document-bytes:26214400}") long maxDocumentBytes
    ) { return new ExtractionService(artifacts,parser,enrichment,extractor,validator,verifier,persistence,writer,unitOfWork,fingerprintLock,observer,reviewPolicy,clock,maxDocumentBytes); }
    @Bean ReviewService reviewService(
        ReviewPersistence persistence,ProposalValidator validator,EvidenceVerifier verifier,
        VerifiedProposalWriter writer,UnitOfWork unitOfWork,Clock clock
    ) { return new ReviewService(persistence,validator,verifier,writer,unitOfWork,clock); }
}
