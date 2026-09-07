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
import com.careeros.application.workbench.WorkbenchPorts.*;
import com.careeros.application.workbench.WorkbenchSummaryService;
import com.careeros.application.planning.CareerPlanPorts.CareerPlanQuery;
import com.careeros.application.planning.CareerPlanService;
import com.careeros.application.personal.CandidateEvidenceTaskPorts.CandidateSnapshot;
import com.careeros.application.personal.CandidateEvidenceTaskPorts.QualificationImpact;
import com.careeros.application.personal.CandidateEvidenceTaskService;
import com.careeros.application.personal.CandidateDecisionDiffService;
import com.careeros.application.personal.PersonalActionPorts.CurrentJobSignal;
import com.careeros.application.personal.PersonalActionPorts.TargetJobChangeSnapshot;
import com.careeros.application.personal.PersonalActionService;
import com.careeros.application.personal.PoliticalRequirementClassifier;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ApplicationConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean EligibilityEvaluator eligibilityEvaluator() { return new EligibilityEvaluator(); }
    @Bean FitEvaluator fitEvaluator() { return new FitEvaluator(); }
    @Bean StabilityEvaluator stabilityEvaluator() { return new StabilityEvaluator(); }
    @Bean CandidateProfileService candidateProfileService(RepositoryPorts.CandidateProfiles candidates, RepositoryPorts.CandidateFactConfirmations facts, Clock clock) { return new CandidateProfileService(candidates, facts, clock); }
    @Bean PoliticalRequirementClassifier politicalRequirementClassifier() { return new PoliticalRequirementClassifier(); }
    @Bean CareerPlanService careerPlanService(CareerPlanQuery query, Clock clock) { return new CareerPlanService(query, clock); }
    @Bean CandidateEvidenceTaskService candidateEvidenceTaskService(
        CandidateProfileService candidateProfiles,
        DecisionRankingService rankings,
        PoliticalRequirementClassifier politicalRequirements
    ) {
        var facts = (com.careeros.application.personal.CandidateEvidenceTaskPorts.CandidateFactsSnapshot) candidateId -> {
            var snapshot = candidateProfiles.facts(candidateId);
            return new CandidateSnapshot(snapshot.profile(), snapshot.statuses());
        };
        var impact = (com.careeros.application.personal.CandidateEvidenceTaskPorts.CandidateQualificationImpact)
            (candidateId, asOf) -> qualificationImpact(rankings, politicalRequirements, candidateId, asOf);
        return new CandidateEvidenceTaskService(facts, impact);
    }
    @Bean PersonalActionService personalActionService(
        DecisionRankingService rankings,
        CandidateEvidenceTaskService evidenceTasks
    ) {
        var jobs = (com.careeros.application.personal.PersonalActionPorts.CurrentJobs) (candidateId, asOf) ->
            ranking(rankings, candidateId, asOf).stream().map(bundle -> new CurrentJobSignal(
                bundle.decision().jobPostingId(), bundle.jobContext().job().title(),
                bundle.jobContext().organization().name(), bundle.decision().eligibilityStatus(),
                bundle.decision().tier(), bundle.jobContext().event().applicationEndsOn())).toList();
        var changes = (com.careeros.application.personal.PersonalActionPorts.TargetJobChanges) (candidateId, asOf) ->
            new TargetJobChangeSnapshot(true, null, List.of());
        return new PersonalActionService(jobs, evidenceTasks::tasks, changes);
    }
    @Bean DecisionIntelligenceService decisionIntelligenceService(RepositoryPorts.CandidateProfiles candidates,RepositoryPorts.CandidateFactConfirmations candidateFacts,RepositoryPorts.EligibilityAssessments assessments,DecisionPorts.JobContexts jobContexts,DecisionPorts.OrganizationStabilityFacts stabilityFacts,DecisionPorts.DecisionSnapshots snapshots,JobAdmissionPorts.JobAdmissions admissions,DecisionPorts.DecisionInputLock inputLock,EligibilityEvaluator eligibilityEvaluator,FitEvaluator fitEvaluator,StabilityEvaluator stabilityEvaluator) { return new DecisionIntelligenceService(candidates,candidateFacts,assessments,jobContexts,stabilityFacts,snapshots,admissions,inputLock,eligibilityEvaluator,fitEvaluator,stabilityEvaluator); }
    @Bean DecisionRankingService decisionRankingService(DecisionPorts.JobContexts jobContexts,JobAdmissionPorts.JobAdmissions admissions,DecisionIntelligenceService decisions) { return new DecisionRankingService(jobContexts,admissions,decisions); }
    @Bean CandidateDecisionDiffService candidateDecisionDiffService(RepositoryPorts.CandidateProfiles candidates, DecisionPorts.DecisionSnapshots snapshots, DecisionIntelligenceService decisions, Clock clock) { return new CandidateDecisionDiffService(candidates, snapshots, decisions, clock); }
    @Bean DecisionExplanationService decisionExplanationService() { return new DecisionExplanationService(); }
    @Bean JobLibrarySummaryService jobLibrarySummaryService(JobAdmissionPorts.JobAdmissions admissions) { return new JobLibrarySummaryService(admissions); }
    @Bean OfficialJobAdmissionService officialJobAdmissionService(DecisionPorts.JobContexts jobContexts,JobAdmissionPorts.JobAdmissions admissions,JobAdmissionPorts.JobFieldEvidence fieldEvidence) { return new OfficialJobAdmissionService(jobContexts,admissions,fieldEvidence); }
    @Bean CandidateMatchService candidateMatchService(RepositoryPorts.CandidateProfiles candidates,RepositoryPorts.CandidateFactConfirmations facts,DecisionPorts.JobContexts jobContexts,JobAdmissionPorts.JobAdmissions admissions,EligibilityEvaluator eligibilityEvaluator,FitEvaluator fitEvaluator,JobAdmissionPorts.JobFieldEvidence fieldEvidence) { return new CandidateMatchService(candidates,facts,jobContexts,admissions,eligibilityEvaluator,fitEvaluator,fieldEvidence); }
    @Bean WorkbenchSummaryService workbenchSummaryService(
        DecisionRankingService rankings, AcquisitionStore acquisitionStore,
        ReviewPersistence reviewPersistence, Clock clock
    ) {
        DecisionOverview decisions = (candidateId, now) -> rankings.rank(candidateId,
            new DecisionRankingService.RankingQuery(null, null, null, 0, 100, true), now)
            .items().stream().map(bundle -> new DecisionSignal(
                bundle.decision().jobPostingId(), bundle.jobContext().job().title(),
                bundle.jobContext().organization().name(), bundle.jobContext().job().location(),
                bundle.decision().eligibilityStatus(), bundle.decision().tier(),
                bundle.decision().fitScore(), bundle.decision().stabilityScore(),
                bundle.decision().coveragePercent(), bundle.jobContext().event().applicationEndsOn())).toList();
        AcquisitionOverview acquisition = now -> new AcquisitionSnapshot(
            acquisitionStore.findChanges(null, null, Set.of(), 10).items().stream()
                .map(value -> new ChangeSignal(value.id(), value.changeType().name(), value.canonicalUri(), value.jobDeltaSummary(), value.occurredAt())).toList(),
            acquisitionStore.findSources().stream()
                .map(value -> new SourceSignal(value.id(), value.name(), value.enabled(), value.lastSuccessAt(), value.lastFailureAt(), value.consecutiveFailureCount())).toList());
        ReviewOverview reviews = () -> reviewPersistence.findPage(
            com.careeros.domain.DomainEnums.ReviewStatus.PENDING, 0, 1).totalElements();
        return new WorkbenchSummaryService(decisions, acquisition, reviews, clock);
    }
    @Bean CareerDecisionService careerDecisionService(RepositoryPorts.JobPostings jobs,RepositoryPorts.Opportunities opportunities,JobAdmissionPorts.JobAdmissions admissions) { return new CareerDecisionService(jobs,opportunities,admissions); }
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

    @Bean SourceDiscoverer sourceDiscoverer() {
        return new RoutingSourceDiscoverer(
            new StaticHtmlSourceDiscoverer(), new HospitalOfficialEvidenceDiscoverer());
    }
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
    @Bean SourceListingReader sourceListingReader(
        DocumentFetcher acquisitionDocumentFetcher, SourceDiscoverer sourceDiscoverer
    ) {
        return new ConfigurableSourceListingReader(acquisitionDocumentFetcher, sourceDiscoverer);
    }
    @Bean NextRunCalculator nextRunCalculator() {
        return (source, after) -> {
            var cron = org.springframework.scheduling.support.CronExpression.parse(source.cronExpression());
            ZonedDateTime next = cron.next(ZonedDateTime.ofInstant(after, ZoneId.of(source.timeZone())));
            if (next == null) throw new IllegalArgumentException("Cron has no next execution: " + source.cronExpression());
            return next.toInstant();
        };
    }
    @Bean SourceConnectionProjector sourceConnectionProjector(AcquisitionStore store) {
        return new SourceConnectionProjector(store, Duration.ofHours(48));
    }
    @Bean SourceCompletionAuditService sourceCompletionAuditService(
        AcquisitionStore store, SourceCompletionAuditPorts.AuditSnapshots snapshots, Clock clock) {
        return new SourceCompletionAuditService(store, snapshots, clock);
    }
    @Bean AcquisitionService acquisitionService(
        AcquisitionStore store, SourceRunLock sourceRunLock, SourceDiscoverer sourceDiscoverer,
        SourceListingReader sourceListingReader, DocumentFetcher acquisitionDocumentFetcher,
        AttachmentDiscoverer attachmentDiscoverer,
        AcquiredDocumentProcessor processor, ArtifactStore artifacts, NextRunCalculator nextRunCalculator,
        AcquisitionObserver acquisitionObserver, SourceConnectionProjector sourceConnectionProjector,
        Clock clock, @Value("${career-os.acquisition.max-document-bytes:26214400}") long maxDocumentBytes
    ) {
        return new AcquisitionService(store, sourceRunLock, sourceDiscoverer, sourceListingReader,
            acquisitionDocumentFetcher,
            attachmentDiscoverer, processor, artifacts, nextRunCalculator, acquisitionObserver,
            sourceConnectionProjector, clock, maxDocumentBytes);
    }

    private static QualificationImpact qualificationImpact(
        DecisionRankingService rankings,
        PoliticalRequirementClassifier politicalRequirements,
        java.util.UUID candidateId,
        LocalDate asOf
    ) {
        var counts = new EnumMap<com.careeros.domain.CandidateFacts.CandidateFactKey, Integer>(
            com.careeros.domain.CandidateFacts.CandidateFactKey.class);
        for (var bundle : ranking(rankings, candidateId, asOf)) {
            if (bundle.decision().tier() == com.careeros.domain.DomainEnums.OpportunityTier.EXCLUDED) continue;
            var affected = EnumSet.noneOf(com.careeros.domain.CandidateFacts.CandidateFactKey.class);
            bundle.eligibility().ruleResults().forEach((rule, result) -> {
                if (result.status() == com.careeros.domain.DomainEnums.EligibilityStatus.ELIGIBLE) return;
                switch (rule) {
                    case EXPERIENCE -> {
                        affected.add(com.careeros.domain.CandidateFacts.CandidateFactKey.EMPLOYMENT_HISTORY);
                        affected.add(com.careeros.domain.CandidateFacts.CandidateFactKey.EXPERIENCE_YEARS);
                    }
                    case EDUCATION, EXACT_MAJOR, GRADUATE_YEAR ->
                        affected.add(com.careeros.domain.CandidateFacts.CandidateFactKey.EDUCATION_RECORDS);
                    case PROFESSIONAL_TITLE ->
                        affected.add(com.careeros.domain.CandidateFacts.CandidateFactKey.PROFESSIONAL_TITLES);
                    default -> { }
                }
            });
            bundle.fit().dimensions().stream()
                .filter(dimension -> dimension.factStatus() == com.careeros.domain.DomainEnums.AssessmentFactStatus.UNKNOWN)
                .forEach(dimension -> {
                    switch (dimension.type()) {
                        case SKILL_FIT -> affected.add(com.careeros.domain.CandidateFacts.CandidateFactKey.SKILLS);
                        case RESEARCH_FIT -> affected.add(com.careeros.domain.CandidateFacts.CandidateFactKey.RESEARCH_KEYWORDS);
                        case EXPERIENCE_FIT -> affected.add(com.careeros.domain.CandidateFacts.CandidateFactKey.EMPLOYMENT_HISTORY);
                        case PROFESSIONAL_TITLE_FIT -> affected.add(com.careeros.domain.CandidateFacts.CandidateFactKey.PROFESSIONAL_TITLES);
                        default -> { }
                    }
                });
            if (politicalRequirements.hasHardRequirement(bundle.jobContext().job())) {
                affected.add(com.careeros.domain.CandidateFacts.CandidateFactKey.POLITICAL_AFFILIATION);
            }
            affected.forEach(key -> counts.merge(key, 1, Integer::sum));
        }
        return new QualificationImpact(counts);
    }

    private static List<com.careeros.application.DecisionPorts.DecisionBundle> ranking(
        DecisionRankingService rankings,
        java.util.UUID candidateId,
        LocalDate asOf
    ) {
        return rankings.rankAll(candidateId, asOf.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

}
