package com.careeros;

import com.careeros.application.*;
import com.careeros.application.AcquisitionHttpPorts.*;
import com.careeros.application.AcquisitionPorts.*;
import com.careeros.application.ExtractionPorts.*;
import com.careeros.domain.EligibilityEvaluator;
import com.careeros.domain.FitEvaluator;
import com.careeros.domain.StabilityEvaluator;
import com.careeros.domain.ReviewPolicy;
import com.careeros.infrastructure.artifact.FileSystemArtifactStore;
import com.careeros.infrastructure.acquisition.*;
import java.net.http.HttpClient;
import com.careeros.infrastructure.extraction.*;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ApplicationConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean EligibilityEvaluator eligibilityEvaluator() { return new EligibilityEvaluator(); }
    @Bean FitEvaluator fitEvaluator() { return new FitEvaluator(); }
    @Bean StabilityEvaluator stabilityEvaluator() { return new StabilityEvaluator(); }
    @Bean DecisionIntelligenceService decisionIntelligenceService(RepositoryPorts.CandidateProfiles candidates,RepositoryPorts.EligibilityAssessments assessments,DecisionPorts.JobContexts jobContexts,DecisionPorts.OrganizationStabilityFacts stabilityFacts,DecisionPorts.DecisionSnapshots snapshots,EligibilityEvaluator eligibilityEvaluator,FitEvaluator fitEvaluator,StabilityEvaluator stabilityEvaluator) { return new DecisionIntelligenceService(candidates,assessments,jobContexts,stabilityFacts,snapshots,eligibilityEvaluator,fitEvaluator,stabilityEvaluator); }
    @Bean DecisionRankingService decisionRankingService(DecisionPorts.JobContexts jobContexts,DecisionIntelligenceService decisions) { return new DecisionRankingService(jobContexts,decisions); }
    @Bean DecisionExplanationService decisionExplanationService() { return new DecisionExplanationService(); }
    @Bean CareerDecisionService careerDecisionService(RepositoryPorts.CandidateProfiles candidates, RepositoryPorts.JobPostings jobs, RepositoryPorts.EligibilityAssessments assessments, RepositoryPorts.Opportunities opportunities, EligibilityEvaluator evaluator) { return new CareerDecisionService(candidates,jobs,assessments,opportunities,evaluator); }
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

    @Bean StaticHtmlSourceDiscoverer sourceDiscoverer() { return new StaticHtmlSourceDiscoverer(); }
    @Bean HtmlAttachmentDiscoverer attachmentDiscoverer() { return new HtmlAttachmentDiscoverer(); }
    @Bean MediaTypeDetector acquisitionMediaTypeDetector() { return new MediaTypeDetector(); }
    @Bean HttpClient acquisitionHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Bean DocumentFetcher acquisitionDocumentFetcher(
        HttpClient acquisitionHttpClient,
        MediaTypeDetector acquisitionMediaTypeDetector,
        @Value("${career-os.acquisition.http-contact:}") String contact
    ) {
        String suffix = contact == null || contact.isBlank() ? "+private research" : "+" + contact;
        return new JavaHttpDocumentFetcher(acquisitionHttpClient, acquisitionMediaTypeDetector,
            JavaHttpDocumentFetcher.Sleeper.threadSleep(), "CareerOS/0.3 (" + suffix + ")", 5, 2);
    }
    @Bean NextRunCalculator nextRunCalculator() {
        return (source, after) -> {
            var cron = org.springframework.scheduling.support.CronExpression.parse(source.cronExpression());
            ZonedDateTime next = cron.next(ZonedDateTime.ofInstant(after, ZoneId.of(source.timeZone())));
            if (next == null) throw new IllegalArgumentException("Cron has no next execution: " + source.cronExpression());
            return next.toInstant();
        };
    }
    @Bean AcquisitionService acquisitionService(
        AcquisitionStore store, SourceRunLock sourceRunLock, SourceDiscoverer sourceDiscoverer,
        DocumentFetcher acquisitionDocumentFetcher, AttachmentDiscoverer attachmentDiscoverer,
        AcquiredDocumentProcessor processor, ArtifactStore artifacts, NextRunCalculator nextRunCalculator,
        AcquisitionObserver acquisitionObserver,
        Clock clock, @Value("${career-os.acquisition.max-document-bytes:26214400}") long maxDocumentBytes
    ) {
        return new AcquisitionService(store, sourceRunLock, sourceDiscoverer, acquisitionDocumentFetcher,
            attachmentDiscoverer, processor, artifacts, nextRunCalculator, acquisitionObserver, clock, maxDocumentBytes);
    }
}
