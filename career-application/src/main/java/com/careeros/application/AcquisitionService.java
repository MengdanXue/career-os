package com.careeros.application;

import static com.careeros.application.AcquisitionHttpPorts.*;
import static com.careeros.application.AcquisitionPorts.*;

import com.careeros.application.AcquiredDocumentProcessor.ProcessDocumentCommand;
import com.careeros.application.AcquiredDocumentProcessor.ProcessingResult;
import com.careeros.application.ExtractionPorts.ArtifactStore;
import com.careeros.domain.DomainEnums.EventType;
import com.careeros.domain.SourceArtifact;
import com.careeros.domain.acquisition.*;
import com.careeros.domain.acquisition.AcquiredDocument.DocumentKind;
import com.careeros.domain.acquisition.AcquisitionChange.ChangeType;
import com.careeros.domain.acquisition.DocumentTransition.TransitionType;
import com.careeros.domain.acquisition.SourceCrawlRun.RunStatus;
import com.careeros.domain.acquisition.SourceCrawlRun.RunTrigger;
import com.careeros.domain.acquisition.SourceOnboardingCheckpoint.Checkpoint;
import com.careeros.domain.acquisition.SourceOnboardingCheckpoint.CheckpointStatus;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class AcquisitionService {
    private static final Pattern URL_DATE = Pattern.compile("/(20\\d{2})/(\\d{1,2})/(\\d{1,2})/");
    private static final Pattern URL_ARTICLE_YEAR = Pattern.compile("/art/(20\\d{2})(?:/|$)");
    private static final Pattern TITLE_YEAR = Pattern.compile("(20\\d{2})");
    private static final Pattern CONTENT_TYPE_CHARSET = Pattern.compile(
        "(?i)(?:^|;)\\s*charset\\s*=\\s*[\\\"']?([^;\\s\\\"']+)");
    private static final Pattern HTML_META_CHARSET = Pattern.compile(
        "(?i)charset\\s*=\\s*[\\\"']?\\s*([A-Za-z0-9._:-]+)");
    private final AcquisitionStore store;
    private final SourceRunLock lock;
    private final SourceDiscoverer discoverer;
    private final SourceListingReader listings;
    private final DocumentFetcher fetcher;
    private final AttachmentDiscoverer attachments;
    private final AcquiredDocumentProcessor processor;
    private final ArtifactStore artifacts;
    private final NextRunCalculator nextRuns;
    private final AcquisitionObserver observer;
    private final SourceConnectionProjector connectionProjector;
    private final Clock clock;
    private final long maxDocumentBytes;

    public AcquisitionService(
        AcquisitionStore store,
        SourceRunLock lock,
        SourceDiscoverer discoverer,
        DocumentFetcher fetcher,
        AttachmentDiscoverer attachments,
        AcquiredDocumentProcessor processor,
        ArtifactStore artifacts,
        NextRunCalculator nextRuns,
        Clock clock,
        long maxDocumentBytes
    ) {
        this(store, lock, discoverer, null, fetcher, attachments, processor, artifacts, nextRuns,
            AcquisitionObserver.NOOP, null, clock, maxDocumentBytes);
    }

    public AcquisitionService(
        AcquisitionStore store,
        SourceRunLock lock,
        SourceDiscoverer discoverer,
        SourceListingReader listings,
        DocumentFetcher fetcher,
        AttachmentDiscoverer attachments,
        AcquiredDocumentProcessor processor,
        ArtifactStore artifacts,
        NextRunCalculator nextRuns,
        AcquisitionObserver observer,
        SourceConnectionProjector connectionProjector,
        Clock clock,
        long maxDocumentBytes
    ) {
        this.store=Objects.requireNonNull(store); this.lock=Objects.requireNonNull(lock);
        this.discoverer=Objects.requireNonNull(discoverer); this.fetcher=Objects.requireNonNull(fetcher);
        this.listings=listings == null ? this::readLegacySinglePage : listings;
        this.attachments=Objects.requireNonNull(attachments); this.processor=Objects.requireNonNull(processor);
        this.artifacts=Objects.requireNonNull(artifacts); this.nextRuns=Objects.requireNonNull(nextRuns);
        this.observer=Objects.requireNonNull(observer);
        this.connectionProjector=connectionProjector;
        this.clock=Objects.requireNonNull(clock);
        if (maxDocumentBytes < 1) throw new IllegalArgumentException("maxDocumentBytes must be positive");
        this.maxDocumentBytes=maxDocumentBytes;
    }

    public AcquisitionService(
        AcquisitionStore store,
        SourceRunLock lock,
        SourceDiscoverer discoverer,
        DocumentFetcher fetcher,
        AttachmentDiscoverer attachments,
        AcquiredDocumentProcessor processor,
        ArtifactStore artifacts,
        NextRunCalculator nextRuns,
        AcquisitionObserver observer,
        Clock clock,
        long maxDocumentBytes
    ) {
        this(store, lock, discoverer, null, fetcher, attachments, processor, artifacts, nextRuns,
            observer, null, clock, maxDocumentBytes);
    }

    public AcquisitionService(
        AcquisitionStore store,
        SourceRunLock lock,
        SourceDiscoverer discoverer,
        SourceListingReader listings,
        DocumentFetcher fetcher,
        AttachmentDiscoverer attachments,
        AcquiredDocumentProcessor processor,
        ArtifactStore artifacts,
        NextRunCalculator nextRuns,
        AcquisitionObserver observer,
        Clock clock,
        long maxDocumentBytes
    ) {
        this(store, lock, discoverer, listings, fetcher, attachments, processor, artifacts, nextRuns,
            observer, null, clock, maxDocumentBytes);
    }

    public SourceCrawlRun run(UUID sourceId, RunTrigger trigger) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(trigger, "trigger");
        RecruitmentSource source = store.findSource(sourceId);
        Instant started = clock.instant();
        UUID runId = UUID.randomUUID();
        Optional<SourceCrawlRun> executed;
        try {
            executed = lock.tryExecute(source.code(), Duration.ofSeconds(2),
                () -> executeLocked(source, runId, trigger, started));
        } catch (RuntimeException failure) {
            return failedBeforeDiscovery(source, runId, trigger, started, failure);
        }
        if (executed.isPresent()) {
            SourceCrawlRun result = executed.orElseThrow();
            refreshConnection(sourceId);
            observer.runCompleted(source.code(), result);
            return result;
        }
        SourceCrawlRun skipped = terminal(runId, sourceId, trigger, RunStatus.SKIPPED_LOCKED,
            started, new Counts(), "SOURCE_LOCKED", "Another instance is acquiring this source");
        SourceCrawlRun saved = store.saveRun(skipped);
        observer.lockSkipped(source.code());
        observer.runCompleted(source.code(), saved);
        return saved;
    }

    public SourceCrawlRun backfill(UUID sourceId, Set<Integer> recruitmentYears) {
        Objects.requireNonNull(sourceId, "sourceId");
        if (recruitmentYears == null || recruitmentYears.isEmpty()) {
            throw new IllegalArgumentException("recruitmentYears is required");
        }
        Set<Integer> years = new TreeSet<>(recruitmentYears);
        if (years.stream().anyMatch(year -> year < 2000 || year > 2100)) {
            throw new IllegalArgumentException("recruitmentYears must be between 2000 and 2100");
        }
        RecruitmentSource source = store.findSource(sourceId);
        Instant started = clock.instant();
        UUID runId = UUID.randomUUID();
        Optional<SourceCrawlRun> executed;
        try {
            executed = lock.tryExecute(source.code(), Duration.ofSeconds(2),
                () -> executeHistoricalLocked(source, runId, started, years));
        } catch (RuntimeException failure) {
            retainSuccessfulCoverageOrMarkFailed(source.id(), years);
            return failedBeforeDiscovery(source, runId, RunTrigger.MANUAL, started, failure);
        }
        if (executed.isPresent()) {
            SourceCrawlRun result = executed.orElseThrow();
            refreshConnection(sourceId);
            observer.runCompleted(source.code(), result);
            return result;
        }
        SourceCrawlRun skipped = terminal(runId, sourceId, RunTrigger.MANUAL, RunStatus.SKIPPED_LOCKED,
            started, new Counts(), "SOURCE_LOCKED", "Another instance is acquiring this source");
        SourceCrawlRun saved = store.saveRun(skipped);
        observer.lockSkipped(source.code());
        observer.runCompleted(source.code(), saved);
        return saved;
    }

    private void refreshConnection(UUID sourceId) {
        if (connectionProjector != null) connectionProjector.refresh(sourceId, clock.instant());
    }

    private SourceCrawlRun executeHistoricalLocked(
        RecruitmentSource source, UUID runId, Instant started, Set<Integer> recruitmentYears
    ) {
        store.saveRun(SourceCrawlRun.running(runId, source.id(), RunTrigger.MANUAL, started));
        Counts counts = new Counts();
        Map<Integer, YearCounts> byYear = new LinkedHashMap<>();
        recruitmentYears.forEach(year -> byYear.put(year, new YearCounts()));
        try {
            ContentPolicies contentPolicies = ContentPolicies.from(source);
            ListingResult listing = historicalListing(source, recruitmentYears);
            List<YearDiscoveredLink> details = listing.links();
            counts.discovered = details.size();
            for (YearDiscoveredLink detail : details) {
                byYear.get(detail.recruitmentYear()).discovered++;
            }
            for (YearDiscoveredLink annualDetail : details) {
                DiscoveredLink detail = annualDetail.link();
                YearCounts yearCounts = byYear.get(annualDetail.recruitmentYear());
                int failuresBefore = counts.failed;
                DocumentOutcome outcome = acquire(source, contentPolicies, runId, detail, null,
                    DocumentKind.ANNOUNCEMENT, detail.title(), counts);
                yearCounts.record(outcome, counts.failed - failuresBefore);
                if (outcome.document == null) continue;
                List<DiscoveredLink> attachmentLinks = discoverAttachments(source, detail, outcome);
                counts.discovered += attachmentLinks.size();
                yearCounts.discovered += attachmentLinks.size();
                for (DiscoveredLink attachment : attachmentLinks) {
                    failuresBefore = counts.failed;
                    DocumentOutcome attachmentOutcome = acquire(source, contentPolicies, runId, attachment,
                        outcome.document, DocumentKind.ATTACHMENT,
                        detail.title(), counts);
                    yearCounts.record(attachmentOutcome, counts.failed - failuresBefore);
                }
            }
            for (var entry : byYear.entrySet()) {
                int year = entry.getKey();
                YearCounts values = entry.getValue();
                ListingEvidence evidence = listing.evidenceByYear().get(year);
                values.targetJobs = Math.toIntExact(store.countActiveTargetJobs(source.id(), year));
                boolean traversalComplete = evidence != null && evidence.traversalComplete()
                    && evidence.failedCount() == 0;
                SourceYearCoverage.CoverageStatus coverageStatus = values.failed > 0 || !traversalComplete
                    ? SourceYearCoverage.CoverageStatus.PARTIAL
                    : values.targetJobs > 0
                        ? SourceYearCoverage.CoverageStatus.COMPLETE
                        : evidence.acceptedCount() == 0
                            ? SourceYearCoverage.CoverageStatus.NO_TARGET_RECORDS
                            : SourceYearCoverage.CoverageStatus.PARTIAL;
                boolean supportsConclusion = coverageStatus == SourceYearCoverage.CoverageStatus.COMPLETE
                    || coverageStatus == SourceYearCoverage.CoverageStatus.NO_TARGET_RECORDS;
                store.saveSourceYearCoverage(new SourceYearCoverage(source.id(), year, coverageStatus,
                    values.discovered, values.fetched, values.parsed, values.targetJobs,
                    supportsConclusion ? evidence.completionBasis() : null,
                    supportsConclusion ? clock.instant() : null, clock.instant(),
                    evidence == null ? 0 : evidence.pageCount(),
                    evidence == null ? 0 : evidence.filteredCount(),
                    values.failed + (evidence == null ? 0 : evidence.failedCount()),
                    evidence == null ? null : evidence.earliestPublishedOn(),
                    evidence == null ? null : evidence.latestPublishedOn(),
                    evidence == null ? "LISTING_EVIDENCE_MISSING" : evidence.stopReason()));
            }
            RunStatus status = counts.failed == 0 ? RunStatus.SUCCEEDED
                : counts.hasSuccess() ? RunStatus.PARTIALLY_SUCCEEDED : RunStatus.FAILED;
            SourceCrawlRun completed = terminal(runId, source.id(), RunTrigger.MANUAL, status, started, counts,
                counts.failed == 0 ? null : "DOCUMENT_FAILURE",
                counts.failed == 0 ? null : counts.failed + " document(s) failed");
            updateSourceHealth(source, RunTrigger.MANUAL, status, counts.sourceFailures > 0);
            SourceCrawlRun saved = store.saveRun(completed);
            if (counts.fetched > 0 && status != RunStatus.FAILED) {
                verifyCheckpoint(source.id(), Checkpoint.LIVE_SMOKE_VERIFIED,
                    "historical fetch succeeded; fetched=" + counts.fetched);
            }
            Set<Integer> requiredYears = Set.of(2024, 2025, 2026);
            boolean allYearsConclusive = requiredYears.stream().allMatch(year ->
                store.findSourceYearCoverage(source.id(), year).stream()
                    .anyMatch(SourceYearCoverage::hasLegacyCompletionRecord));
            if (status == RunStatus.SUCCEEDED && allYearsConclusive) {
                verifyCheckpoint(source.id(), Checkpoint.BACKFILL_COMPLETE,
                    "verified years=" + requiredYears);
            }
            return saved;
        } catch (RuntimeException failure) {
            counts.failed++;
            recordFailure(runId, source.id(), null, listingFailureStage(failure),
                failure.getClass().getSimpleName(), safeMessage(failure));
            retainSuccessfulCoverageOrMarkFailed(source.id(), recruitmentYears);
            failCheckpoint(source.id(), Checkpoint.LIVE_SMOKE_VERIFIED, safeMessage(failure));
            SourceCrawlRun failed = terminal(runId, source.id(), RunTrigger.MANUAL, RunStatus.FAILED,
                started, counts, failure.getClass().getSimpleName(), safeMessage(failure));
            updateSourceHealth(source, RunTrigger.MANUAL, RunStatus.FAILED, true);
            return store.saveRun(failed);
        }
    }

    private ListingResult historicalListing(RecruitmentSource source, Set<Integer> recruitmentYears) {
        return listings.read(source, new ListingQuery(recruitmentYears, true));
    }

    private static Optional<Integer> linkYear(DiscoveredLink link) {
        LocalDate date = link.publishedOn() == null ? publishedDate(link.uri()) : link.publishedOn();
        if (date != null) return Optional.of(date.getYear());
        var pathYear = URL_ARTICLE_YEAR.matcher(link.uri().getPath());
        if (pathYear.find()) return Optional.of(Integer.parseInt(pathYear.group(1)));
        return titleYear(link.title());
    }

    private void saveCoverage(
        UUID sourceId, Set<Integer> years, SourceYearCoverage.CoverageStatus status,
        Map<Integer, YearCounts> values, String completionBasis, Instant completedAt
    ) {
        Instant now = clock.instant();
        for (int year : years) {
            YearCounts counts = values.getOrDefault(year, new YearCounts());
            store.saveSourceYearCoverage(new SourceYearCoverage(sourceId, year, status,
                counts.discovered, counts.fetched, counts.parsed, counts.targetJobs,
                completionBasis, completedAt, now, 0, 0, counts.failed,
                null, null, status == SourceYearCoverage.CoverageStatus.ACCESS_FAILED
                    ? "LISTING_ACCESS_FAILED" : null));
        }
    }

    private SourceCrawlRun executeLocked(
        RecruitmentSource source, UUID runId, RunTrigger trigger, Instant started
    ) {
        store.saveRun(SourceCrawlRun.running(runId, source.id(), trigger, started));
        Counts counts = new Counts();
        try {
            ContentPolicies contentPolicies = ContentPolicies.from(source);
            ListingResult incrementalListing = discoverIncremental(source);
            List<DiscoveredLink> details = incrementalListing.links().stream()
                .map(YearDiscoveredLink::link)
                .toList();
            boolean allowEmptyIncremental = Boolean.parseBoolean(String.valueOf(
                source.configuration().getOrDefault("allowEmptyIncremental", false)));
            if (details.isEmpty() && !allowEmptyIncremental) {
                throw new FetchFailedException(
                    "Incremental listing yielded zero candidate announcements; discovery contract is unverified");
            }
            if (!details.isEmpty()) {
                verifyCheckpoint(source.id(), Checkpoint.CONTRACT_VERIFIED,
                    "incremental listing parsed candidates=" + details.size());
            }
            counts.discovered = details.size();
            Map<Integer, YearCounts> byYear = new LinkedHashMap<>();
            for (DiscoveredLink detail : details) {
                YearCounts yearCounts = linkYear(detail)
                    .map(year -> byYear.computeIfAbsent(year, ignored -> new YearCounts()))
                    .orElse(null);
                if (yearCounts != null) yearCounts.discovered++;
                int failuresBefore = counts.failed;
                DocumentOutcome outcome = acquire(source, contentPolicies, runId, detail, null,
                    DocumentKind.ANNOUNCEMENT, detail.title(), counts);
                if (yearCounts != null) yearCounts.record(outcome, counts.failed - failuresBefore);
                if (outcome.document == null) continue;
                List<DiscoveredLink> attachmentLinks = discoverAttachments(source, detail, outcome);
                counts.discovered += attachmentLinks.size();
                if (yearCounts != null) yearCounts.discovered += attachmentLinks.size();
                for (DiscoveredLink attachment : attachmentLinks) {
                    failuresBefore = counts.failed;
                    DocumentOutcome attachmentOutcome = acquire(source, contentPolicies, runId, attachment,
                        outcome.document, DocumentKind.ATTACHMENT,
                        detail.title(), counts);
                    if (yearCounts != null) {
                        yearCounts.record(attachmentOutcome, counts.failed - failuresBefore);
                    }
                }
            }
            reconcileListingAbsences(source, runId, details,
                incrementalListing.traversalComplete(), counts);
            saveIncrementalCoverage(source.id(), byYear);
            RunStatus status = counts.failed == 0 ? RunStatus.SUCCEEDED
                : counts.hasSuccess() ? RunStatus.PARTIALLY_SUCCEEDED : RunStatus.FAILED;
            SourceCrawlRun completed = terminal(runId, source.id(), trigger, status, started, counts,
                counts.failed == 0 ? null : "DOCUMENT_FAILURE",
                counts.failed == 0 ? null : counts.failed + " document(s) failed");
            updateSourceHealth(source, trigger, status, counts.sourceFailures > 0);
            SourceCrawlRun saved = store.saveRun(completed);
            if (counts.fetched > 0 && status != RunStatus.FAILED) {
                verifyCheckpoint(source.id(), Checkpoint.LIVE_SMOKE_VERIFIED,
                    "incremental fetch succeeded; fetched=" + counts.fetched);
            }
            boolean backfillVerified = store.findCheckpoints(source.id()).stream().anyMatch(value ->
                value.checkpoint() == Checkpoint.BACKFILL_COMPLETE
                    && value.status() == CheckpointStatus.VERIFIED);
            if (backfillVerified && status == RunStatus.SUCCEEDED
                && counts.added == 0 && counts.updated == 0 && counts.deactivated == 0) {
                verifyCheckpoint(source.id(), Checkpoint.INCREMENTAL_VERIFIED,
                    "idempotent incremental run=" + runId + "; unchanged=" + counts.unchanged);
            }
            return saved;
        } catch (RuntimeException failure) {
            counts.failed++;
            recordFailure(runId, source.id(), null, listingFailureStage(failure),
                failure.getClass().getSimpleName(), safeMessage(failure));
            failCheckpoint(source.id(), Checkpoint.LIVE_SMOKE_VERIFIED, safeMessage(failure));
            SourceCrawlRun failed = terminal(runId, source.id(), trigger, RunStatus.FAILED, started, counts,
                failure.getClass().getSimpleName(), safeMessage(failure));
            updateSourceHealth(source, trigger, RunStatus.FAILED, true);
            return store.saveRun(failed);
        }
    }

    private ListingResult discoverIncremental(RecruitmentSource source) {
        return listings.read(source, new ListingQuery(Set.of(), false));
    }

    private void reconcileListingAbsences(
        RecruitmentSource source, UUID runId, List<DiscoveredLink> current,
        boolean traversalComplete, Counts counts
    ) {
        if (!traversalComplete) return;
        if (!Boolean.parseBoolean(String.valueOf(
            source.configuration().getOrDefault("listingAbsenceDeactivationEnabled", false)))) return;
        Set<URI> present = current.stream().map(DiscoveredLink::uri)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Instant now = clock.instant();
        for (AcquiredDocument previous : store.findDocuments(source.id())) {
            if (previous.kind() != DocumentKind.ANNOUNCEMENT
                || previous.parentDocumentId() != null || present.contains(previous.canonicalUri())) continue;
            DocumentTransition transition = DocumentTransition.decide(
                previous, FetchObservation.gone(previous.canonicalUri(), 410), now);
            if (transition.type() != TransitionType.DEACTIVATED) {
                store.saveDocument(transition.document());
                continue;
            }
            var change = new AcquisitionChange(UUID.randomUUID(), runId, source.id(), previous.id(),
                ChangeType.DEACTIVATED, previous.contentFingerprint(),
                AcquisitionChange.DEACTIVATED_FINGERPRINT, previous.canonicalUri(),
                Map.of("reason", "MISSING_FROM_SUCCESSFUL_LISTING", "consecutiveMisses",
                    transition.document().consecutiveGoneCount()), now);
            store.saveDocumentAndChange(transition.document(), change);
            counts.deactivated++;
            observer.document(source.code(), "DEACTIVATED");
        }
    }

    private ListingResult readLegacySinglePage(RecruitmentSource source, ListingQuery query) {
        FetchedDocument list = fetchTimed(source, request(source, listingUri(source), null));
        if (list.status() != 200 || list.content().length == 0) {
            throw new FetchFailedException("List page did not return content: " + list.status());
        }
        int currentYear = clock.instant().atZone(ZoneId.of(source.timeZone())).getYear();
        List<YearDiscoveredLink> links = discoverer.discover(source, list.finalUri(), list.content()).stream()
            .map(link -> new YearDiscoveredLink(link, linkYear(link).orElse(currentYear)))
            .filter(link -> !query.historical() || query.recruitmentYears().contains(link.recruitmentYear()))
            .toList();
        if (!query.historical()) return new ListingResult(links, Map.of());
        Map<Integer, ListingEvidence> incomplete = new LinkedHashMap<>();
        for (int year : query.recruitmentYears()) {
            int annual = (int) links.stream().filter(link -> link.recruitmentYear() == year).count();
            incomplete.put(year, new ListingEvidence(1, links.size(), annual, 0, 0,
                null, null, false, "LEGACY_SINGLE_PAGE_INCOMPLETE", null));
        }
        return new ListingResult(links, incomplete);
    }

    private void saveIncrementalCoverage(UUID sourceId, Map<Integer, YearCounts> byYear) {
        Instant now = clock.instant();
        for (var entry : byYear.entrySet()) {
            int year = entry.getKey();
            YearCounts values = entry.getValue();
            SourceYearCoverage previous = store.findSourceYearCoverage(sourceId, year).stream()
                .findFirst().orElse(null);
            if (previous != null && (previous.hasLegacyCompletionRecord()
                || previous.status() == SourceYearCoverage.CoverageStatus.PARTIAL
                    && !"INCREMENTAL_WINDOW_ONLY".equals(previous.stopReason()))) {
                continue;
            }
            values.targetJobs = Math.toIntExact(store.countActiveTargetJobs(sourceId, year));
            store.saveSourceYearCoverage(new SourceYearCoverage(sourceId, year,
                SourceYearCoverage.CoverageStatus.PARTIAL,
                max(previous, SourceYearCoverage::discoveredCount, values.discovered),
                max(previous, SourceYearCoverage::fetchedCount, values.fetched),
                max(previous, SourceYearCoverage::parsedCount, values.parsed),
                values.targetJobs,
                null, null, now, 0, 0, values.failed, null, null, "INCREMENTAL_WINDOW_ONLY"));
        }
    }

    private static int max(
        SourceYearCoverage previous,
        java.util.function.ToIntFunction<SourceYearCoverage> getter,
        int current
    ) {
        return previous == null ? current : Math.max(getter.applyAsInt(previous), current);
    }

    private DocumentOutcome acquire(
        RecruitmentSource source,
        ContentPolicies contentPolicies,
        UUID runId,
        DiscoveredLink link,
        AcquiredDocument parent,
        DocumentKind kind,
        String announcementTitle,
        Counts counts
    ) {
        Optional<AcquiredDocument> prior = store.findDocument(source.id(), link.uri());
        recordDiscovery(source, runId, link, parent, kind,
            ArtifactDiscovery.DiscoveryStatus.DISCOVERED, null);
        FetchedDocument response;
        try {
            response = fetchTimed(source, request(source, link, prior.orElse(null)));
            validateResponsePolicies(contentPolicies, response);
        } catch (RuntimeException failure) {
            counts.failed++;
            counts.sourceFailures++;
            recordFailure(runId, source.id(), prior.map(AcquiredDocument::id).orElse(null),
                ArtifactImportFailure.FailureStage.ARTIFACT_DOWNLOAD_FAILED,
                failure.getClass().getSimpleName(), safeMessage(failure));
            recordDiscovery(source, runId, link, parent, kind,
                ArtifactDiscovery.DiscoveryStatus.FETCH_FAILED, failure.getClass().getSimpleName());
            observer.document(source.code(), "FETCH_FAILED");
            return new DocumentOutcome(prior.orElse(null), null, null, false);
        }
        if (prior.isEmpty() && response.gone()) {
            counts.failed++;
            counts.sourceFailures++;
            recordFailure(runId, source.id(), null,
                ArtifactImportFailure.FailureStage.ARTIFACT_DOWNLOAD_FAILED,
                "DOCUMENT_GONE", "Official document returned HTTP " + response.status());
            recordDiscovery(source, runId, link, parent, kind,
                ArtifactDiscovery.DiscoveryStatus.FETCH_FAILED, "DOCUMENT_GONE");
            return new DocumentOutcome(null, response, null, false);
        }
        recordDiscovery(source, runId, link, parent, kind,
            ArtifactDiscovery.DiscoveryStatus.FETCHED, null);
        FetchObservation observation = observation(contentPolicies, response, link.uri());
        DocumentTransition transition;
        try {
            transition = DocumentTransition.decide(prior.orElse(null), observation, clock.instant());
        } catch (RuntimeException failure) {
            counts.failed++;
            recordFailure(runId, source.id(), prior.map(AcquiredDocument::id).orElse(null),
                ArtifactImportFailure.FailureStage.DOCUMENT_PARSE_FAILED,
                failure.getClass().getSimpleName(), safeMessage(failure));
            recordDiscovery(source, runId, link, parent, kind,
                ArtifactDiscovery.DiscoveryStatus.PARSE_FAILED, failure.getClass().getSimpleName());
            return new DocumentOutcome(prior.orElse(null), response, null, false);
        }
        AcquiredDocument document = transition.document();
        if (response.content().length > 0) {
            SourceArtifact artifact = artifacts.put(response.content(), response.mediaType(), clock.instant());
            document = transition.bind(source.id(), parent == null ? null : parent.id(), kind,
                URI.create(artifact.storageUri()));
        }
        String listingMetadataFingerprint = kind == DocumentKind.ANNOUNCEMENT
            ? listingMetadataFingerprint(link) : null;
        boolean listingMetadataChanged = prior
            .map(AcquiredDocument::listingMetadataFingerprint)
            .filter(Objects::nonNull)
            .map(previous -> !previous.equals(listingMetadataFingerprint))
            .orElse(false);
        if (listingMetadataFingerprint != null) {
            document = document.withListingMetadataFingerprint(listingMetadataFingerprint,
                listingMetadataChanged ? clock.instant() : document.lastChangedAt());
        }
        ProcessingResult processing = null;
        boolean processorChanged = !Objects.equals(document.lastProcessorVersion(), processor.version());
        if (transition.shouldProcess() || processorChanged || listingMetadataChanged) {
            try {
                byte[] content = response.content().length > 0 ? response.content() : readStored(document);
                processing = processor.process(processCommand(
                    source, link, parent, document, content, announcementTitle));
                if (processing.successful()) {
                    if (processing.status() == AcquiredDocumentProcessor.ProcessingStatus.PROCESSED_WITH_ERRORS
                        || processing.status() == AcquiredDocumentProcessor.ProcessingStatus.OCR_REQUIRED) {
                        document = document.processed(document.contentFingerprint(), processor.version() + ":partial");
                        counts.failed++;
                        observer.processingFailure(source.code(), document.mediaType());
                    } else {
                        document = document.processed(document.contentFingerprint(), processor.version());
                    }
                }
                else {
                    counts.failed++;
                    observer.processingFailure(source.code(), document.mediaType());
                }
            } catch (RuntimeException failure) {
                counts.failed++;
                processing = ProcessingResult.failed(failure.getClass().getSimpleName());
                observer.processingFailure(source.code(), document.mediaType());
            }
        }
        boolean listingMetadataOnlyUpdate = listingMetadataChanged
            && transition.type() == TransitionType.UNCHANGED;
        ChangeType changeType = listingMetadataOnlyUpdate
            ? ChangeType.UPDATED : changeType(transition.type());
        if (changeType == null) {
            document = store.saveDocument(document);
            counts.unchanged++;
        } else {
            String current = changeType == ChangeType.DEACTIVATED
                ? AcquisitionChange.DEACTIVATED_FINGERPRINT
                : listingMetadataOnlyUpdate ? listingMetadataFingerprint
                    : document.contentFingerprint();
            String previous = listingMetadataOnlyUpdate
                ? prior.map(AcquiredDocument::listingMetadataFingerprint).orElse(null)
                : transition.previousFingerprint();
            Map<String, Object> summary = new LinkedHashMap<>(
                processing == null ? Map.of() : processing.summary());
            if (listingMetadataChanged) summary.put("listingMetadataChanged", true);
            var change = new AcquisitionChange(UUID.randomUUID(), runId, source.id(), document.id(), changeType,
                previous, current, document.canonicalUri(),
                summary, clock.instant());
            document = store.saveDocumentAndChange(document, change).document();
            switch (changeType) {
                case ADDED -> counts.added++;
                case UPDATED -> counts.updated++;
                case DEACTIVATED -> counts.deactivated++;
            }
        }
        if (processing != null && !processing.issues().isEmpty()) {
            AcquiredDocument persistedDocument = document;
            List<ArtifactImportFailure> failures = processing.issues().stream().map(issue ->
                new ArtifactImportFailure(UUID.randomUUID(), runId, source.id(), persistedDocument.id(),
                    issue.stage(), issue.sheetName(), issue.rowNumber(), issue.errorCode(),
                    issue.safeMessage(), clock.instant())).toList();
            store.saveImportFailures(failures);
        }
        if (processing != null && (processing.status() == AcquiredDocumentProcessor.ProcessingStatus.UNSUPPORTED
            || processing.status() == AcquiredDocumentProcessor.ProcessingStatus.FAILED)) {
            ArtifactImportFailure.FailureStage stage = processing.status()
                == AcquiredDocumentProcessor.ProcessingStatus.UNSUPPORTED
                ? ArtifactImportFailure.FailureStage.UNSUPPORTED_DOCUMENT
                : ArtifactImportFailure.FailureStage.DOCUMENT_PARSE_FAILED;
            recordFailure(runId, source.id(), document.id(), stage,
                processing.errorCode() == null ? processing.status().name() : processing.errorCode(),
                "Document processing ended with status " + processing.status());
        }
        if (processing != null && (processing.status() == AcquiredDocumentProcessor.ProcessingStatus.UNSUPPORTED
            || processing.status() == AcquiredDocumentProcessor.ProcessingStatus.FAILED
            || processing.status() == AcquiredDocumentProcessor.ProcessingStatus.PROCESSED_WITH_ERRORS
            || processing.status() == AcquiredDocumentProcessor.ProcessingStatus.OCR_REQUIRED)) {
            recordDiscovery(source, runId, link, parent, kind,
                ArtifactDiscovery.DiscoveryStatus.PARSE_FAILED,
                processing.errorCode() == null ? processing.status().name() : processing.errorCode());
        } else if (processing != null && processing.successful()) {
            recordDiscovery(source, runId, link, parent, kind,
                ArtifactDiscovery.DiscoveryStatus.PROCESSED, null);
        }
        counts.fetched++;
        observer.document(source.code(), transition.type().name());
        boolean parsed = document.lastProcessedFingerprint() != null
            && document.lastProcessedFingerprint().equals(document.contentFingerprint());
        return new DocumentOutcome(document, response, processing, parsed);
    }

    private void recordDiscovery(
        RecruitmentSource source, UUID runId, DiscoveredLink link, AcquiredDocument parent,
        DocumentKind kind, ArtifactDiscovery.DiscoveryStatus status, String errorCode
    ) {
        Instant now = clock.instant();
        UUID id = UUID.nameUUIDFromBytes(("artifact-discovery|" + source.id() + "|" + link.uri())
            .getBytes(StandardCharsets.UTF_8));
        store.saveArtifactDiscovery(new ArtifactDiscovery(id, source.id(), runId,
            parent == null ? null : parent.id(), link.uri(), link.fetchUri(), link.title(), kind,
            link.publishedOn(), status, errorCode, now, now, 1));
    }

    private FetchedDocument fetchTimed(RecruitmentSource source, FetchRequest request) {
        Instant started = clock.instant();
        try { return fetcher.fetch(request); }
        finally { observer.fetch(source.code(), Duration.between(started, clock.instant())); }
    }

    private List<DiscoveredLink> discoverAttachments(
        RecruitmentSource source, DiscoveredLink detail, DocumentOutcome outcome
    ) {
        List<DiscoveredLink> discovered;
        if (outcome.response != null && outcome.response.content().length > 0
            && isHtml(outcome.response.mediaType())) {
            discovered = attachments.discover(source, detail, outcome.response.content());
        } else if (isHtml(outcome.document.mediaType())) {
            discovered = attachments.discover(source, detail, readStored(outcome.document));
        } else {
            discovered = store.findDocuments(source.id()).stream()
                .filter(document -> outcome.document.id().equals(document.parentDocumentId()))
                .map(document -> new DiscoveredLink(
                    document.canonicalUri(), detail.title(), detail.readContract(), detail.publishedOn()))
                .toList();
        }
        if (detail.publishedOn() == null) return discovered;
        return discovered.stream().map(attachment -> attachment.publishedOn() == null
            ? new DiscoveredLink(attachment.uri(), attachment.title(),
                attachment.readContract(), detail.publishedOn())
            : attachment).toList();
    }

    private static boolean isHtml(String mediaType) {
        return mediaType != null && (mediaType.startsWith("text/html")
            || mediaType.startsWith("application/xhtml+xml"));
    }

    private FetchRequest request(RecruitmentSource source, DiscoveredLink link, AcquiredDocument prior) {
        URI uri = link.fetchUri();
        if (link.readContract() != null) {
            return new FetchRequest(uri, link.readContract().exactHosts(),
                prior == null ? null : prior.etag(), prior == null ? null : prior.lastModified(),
                Duration.ofSeconds(20), maxDocumentBytes, source.id(), source.minimumRequestInterval(),
                FetchMethod.GET, link.readContract(), Map.of(), null,
                link.responseBodyJsonPath());
        }
        Set<String> hosts = new LinkedHashSet<>();
        hosts.add(source.baseUri().getHost());
        hosts.add(source.entryUri().getHost());
        Object configured = source.configuration().get("allowedHosts");
        if (configured instanceof Collection<?> values) values.stream().map(Object::toString).forEach(hosts::add);
        return new FetchRequest(uri, hosts, prior == null ? null : prior.etag(),
            prior == null ? null : prior.lastModified(), Duration.ofSeconds(20), maxDocumentBytes,
            source.id(), source.minimumRequestInterval(), FetchMethod.GET,
            new HttpReadContract(TransportPolicy.HTTPS_ONLY, hosts, Set.of()), Map.of(), null,
            link.responseBodyJsonPath());
    }

    private FetchRequest request(RecruitmentSource source, URI uri, AcquiredDocument prior) {
        return request(source, new DiscoveredLink(uri, "official listing"), prior);
    }

    private static URI listingUri(RecruitmentSource source) {
        Object configured = source.configuration().get("listingApiUri");
        if (configured == null) return source.entryUri();
        URI uri = URI.create(configured.toString());
        if (!"https".equalsIgnoreCase(uri.getScheme())
            || !source.baseUri().getHost().equalsIgnoreCase(uri.getHost())) {
            throw new IllegalArgumentException("listingApiUri must be HTTPS on the official source host");
        }
        return uri;
    }

    private ProcessDocumentCommand processCommand(
        RecruitmentSource source, DiscoveredLink link, AcquiredDocument parent,
        AcquiredDocument document, byte[] content, String announcementTitle
    ) {
        URI announcementUri = parent == null ? document.canonicalUri() : parent.canonicalUri();
        LocalDate published = link.publishedOn() == null
            ? publishedDate(announcementUri) : link.publishedOn();
        int year = published == null ? titleYear(link.title()).orElse(
            clock.instant().atZone(ZoneId.of(source.timeZone())).getYear()) : published.getYear();
        return new ProcessDocumentCommand(content, document.mediaType(), document.canonicalUri(), announcementUri,
            announcementTitle, clock.instant(), year, published, source.region(), eventType(source));
    }

    private byte[] readStored(AcquiredDocument document) {
        var artifact = new SourceArtifact(UUID.nameUUIDFromBytes(document.contentFingerprint().getBytes(StandardCharsets.US_ASCII)),
            document.contentFingerprint(), document.mediaType(), 0, document.storageUri().toString(), clock.instant());
        try (var input = artifacts.open(artifact)) { return input.readAllBytes(); }
        catch (IOException exception) { throw new IllegalStateException("Could not reopen acquired artifact", exception); }
    }

    private void updateSourceHealth(
        RecruitmentSource source, RunTrigger trigger, RunStatus status, boolean sourceFailure
    ) {
        Instant now = clock.instant();
        Instant next = trigger == RunTrigger.MANUAL ? source.nextDueAt() : nextRuns.next(source, now);
        store.saveSource(status == RunStatus.FAILED || sourceFailure
            ? source.failed(now, next) : source.succeeded(now, next));
    }

    private SourceCrawlRun failedBeforeDiscovery(
        RecruitmentSource source, UUID runId, RunTrigger trigger, Instant started, RuntimeException failure
    ) {
        Counts counts = new Counts(); counts.failed = 1;
        SourceCrawlRun run = terminal(runId, source.id(), trigger, RunStatus.FAILED, started, counts,
            failure.getClass().getSimpleName(), safeMessage(failure));
        updateSourceHealth(source, trigger, RunStatus.FAILED, true);
        failCheckpoint(source.id(), Checkpoint.LIVE_SMOKE_VERIFIED, safeMessage(failure));
        SourceCrawlRun saved = store.saveRun(run);
        recordFailure(runId, source.id(), null,
            ArtifactImportFailure.FailureStage.REMOTE_ACCESS_FAILED,
            failure.getClass().getSimpleName(), safeMessage(failure));
        observer.runCompleted(source.code(), saved);
        return saved;
    }

    private SourceCrawlRun terminal(
        UUID id, UUID sourceId, RunTrigger trigger, RunStatus status, Instant started,
        Counts counts, String errorCode, String errorMessage
    ) {
        return new SourceCrawlRun(id, sourceId, trigger, status, started, clock.instant(), counts.discovered,
            counts.fetched, counts.unchanged, counts.added, counts.updated, counts.deactivated,
            counts.failed, errorCode, errorMessage);
    }

    private static FetchObservation observation(
        ContentPolicies contentPolicies, FetchedDocument response, URI stableUri
    ) {
        AcquiredDocument.TransportRisk risk = switch (response.transportRisk()) {
            case NONE -> AcquiredDocument.TransportRisk.NONE;
            case PLAINTEXT_OFFICIAL_HTTP -> AcquiredDocument.TransportRisk.PLAINTEXT_OFFICIAL_HTTP;
        };
        if (response.notModified()) return new FetchObservation(stableUri, 304,
            FetchObservation.ObservationType.NOT_MODIFIED, null, null, response.etag(),
            response.lastModified(), risk);
        if (response.gone()) return new FetchObservation(stableUri, response.status(),
            FetchObservation.ObservationType.GONE, null, null, null, null, risk);
        if (response.status() != 200) return new FetchObservation(stableUri, response.status(),
            FetchObservation.ObservationType.FAILURE, null, null, null, null, risk);
        return FetchObservation.ok(stableUri, response.status(),
            sha256(fingerprintContent(contentPolicies, response)),
            response.mediaType(), response.etag(), response.lastModified(), risk);
    }

    private static void validateResponsePolicies(
        ContentPolicies contentPolicies, FetchedDocument response
    ) {
        if (contentPolicies.responseRejectPatterns().isEmpty()
            || !isHtml(response.mediaType()) || response.content().length == 0) return;
        String html = decodeHtml(response);
        for (Pattern pattern : contentPolicies.responseRejectPatterns()) {
            if (pattern.matcher(html).find()) {
                throw new FetchFailedException("Official source returned a configured rejection response");
            }
        }
    }

    private static byte[] fingerprintContent(
        ContentPolicies contentPolicies, FetchedDocument response
    ) {
        if (contentPolicies.fingerprintIgnorePatterns().isEmpty()
            || !isHtml(response.mediaType()) || response.content().length == 0) {
            return response.content();
        }
        String normalized = decodeHtml(response);
        for (Pattern pattern : contentPolicies.fingerprintIgnorePatterns()) {
            normalized = pattern.matcher(normalized)
                .replaceAll("<ignored-dynamic-content>");
        }
        return normalized.getBytes(StandardCharsets.UTF_8);
    }

    private static List<Pattern> configuredPatterns(
        RecruitmentSource source, String key
    ) {
        Object configured = source.configuration().get(key);
        if (configured == null) return List.of();
        if (!(configured instanceof Collection<?> values)) {
            throw new SourceConfigurationException(key + " must be an array of regex strings");
        }
        List<Pattern> patterns = new ArrayList<>(values.size());
        int index = 0;
        for (Object value : values) {
            if (!(value instanceof String regex) || regex.isBlank()) {
                throw new SourceConfigurationException(
                    key + "[" + index + "] must be a nonblank regex string");
            }
            try {
                patterns.add(Pattern.compile(regex, Pattern.DOTALL));
            } catch (PatternSyntaxException failure) {
                throw new SourceConfigurationException(
                    key + "[" + index + "] is invalid: " + failure.getDescription(), failure);
            }
            index++;
        }
        return List.copyOf(patterns);
    }

    private static String decodeHtml(FetchedDocument response) {
        byte[] content = response.content();
        return new String(content, htmlCharset(response.mediaType(), content));
    }

    private static Charset htmlCharset(String contentType, byte[] content) {
        if (contentType != null) {
            var matcher = CONTENT_TYPE_CHARSET.matcher(contentType);
            if (matcher.find()) {
                Optional<Charset> declared = supportedCharset(matcher.group(1));
                if (declared.isPresent()) return declared.orElseThrow();
            }
        }
        if (startsWith(content, 0xEF, 0xBB, 0xBF)) return StandardCharsets.UTF_8;
        if (startsWith(content, 0xFE, 0xFF)) return StandardCharsets.UTF_16BE;
        if (startsWith(content, 0xFF, 0xFE)) return StandardCharsets.UTF_16LE;
        String prefix = new String(content, 0, Math.min(content.length, 8_192),
            StandardCharsets.ISO_8859_1);
        var meta = HTML_META_CHARSET.matcher(prefix);
        if (meta.find()) {
            Optional<Charset> declared = supportedCharset(meta.group(1));
            if (declared.isPresent()) return declared.orElseThrow();
        }
        return StandardCharsets.UTF_8;
    }

    private static Optional<Charset> supportedCharset(String name) {
        try {
            return Optional.of(Charset.forName(name));
        } catch (IllegalCharsetNameException | UnsupportedCharsetException ignored) {
            return Optional.empty();
        }
    }

    private static boolean startsWith(byte[] value, int... prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (Byte.toUnsignedInt(value[index]) != prefix[index]) return false;
        }
        return true;
    }

    private static String listingMetadataFingerprint(DiscoveredLink link) {
        byte[] title = link.title().getBytes(StandardCharsets.UTF_8);
        byte[] published = (link.publishedOn() == null ? "" : link.publishedOn().toString())
            .getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(title.length).array());
            digest.update(title);
            digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(published.length).array());
            digest.update(published);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ChangeType changeType(TransitionType type) {
        return switch (type) {
            case ADDED -> ChangeType.ADDED;
            case UPDATED -> ChangeType.UPDATED;
            case DEACTIVATED -> ChangeType.DEACTIVATED;
            case UNCHANGED, NONE -> null;
        };
    }

    private static Optional<Integer> titleYear(String title) {
        var matcher = TITLE_YEAR.matcher(title);
        return matcher.find() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
    }

    private static LocalDate publishedDate(URI uri) {
        var matcher = URL_DATE.matcher(uri.getPath());
        if (!matcher.find()) return null;
        try { return LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))); }
        catch (DateTimeException ignored) { return null; }
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }
    private static String safeMessage(Throwable value) {
        return ArtifactImportFailure.sanitize(
            value.getMessage() == null ? value.getClass().getName() : value.getMessage());
    }

    private static EventType eventType(RecruitmentSource source) {
        return switch (source.sourceType()) {
            case OFFICIAL_UNIVERSITY -> EventType.UNIVERSITY;
            case OFFICIAL_SOE -> EventType.STATE_OWNED_ENTERPRISE;
            default -> source.code().contains("HOSPITAL") ? EventType.HOSPITAL : EventType.PUBLIC_INSTITUTION;
        };
    }

    private void verifyCheckpoint(UUID sourceId, Checkpoint checkpoint, String evidence) {
        store.saveCheckpoint(new SourceOnboardingCheckpoint(sourceId, checkpoint,
            CheckpointStatus.VERIFIED, evidence, clock.instant()));
    }

    private void failCheckpoint(UUID sourceId, Checkpoint checkpoint, String evidence) {
        boolean alreadyVerified = store.findCheckpoints(sourceId).stream().anyMatch(value ->
            value.checkpoint() == checkpoint && value.status() == CheckpointStatus.VERIFIED);
        if (alreadyVerified) return;
        store.saveCheckpoint(new SourceOnboardingCheckpoint(sourceId, checkpoint,
            CheckpointStatus.FAILED, evidence, clock.instant()));
    }

    private void retainSuccessfulCoverageOrMarkFailed(UUID sourceId, Set<Integer> years) {
        for (int year : years) {
            boolean retained = store.findSourceYearCoverage(sourceId, year).stream()
                .anyMatch(SourceYearCoverage::hasLegacyCompletionRecord);
            if (!retained) {
                saveCoverage(sourceId, Set.of(year), SourceYearCoverage.CoverageStatus.ACCESS_FAILED,
                    Map.of(), null, null);
            }
        }
    }

    private void recordFailure(
        UUID runId, UUID sourceId, UUID documentId, ArtifactImportFailure.FailureStage stage,
        String errorCode, String message
    ) {
        store.saveImportFailures(List.of(new ArtifactImportFailure(UUID.randomUUID(), runId, sourceId,
            documentId, stage, null, null, errorCode, message, clock.instant())));
    }

    private static ArtifactImportFailure.FailureStage listingFailureStage(Throwable failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (failure instanceof FetchRejectedException
            || causedBy(failure, java.io.IOException.class)
            || message.contains("HTTP request") || message.contains("HTTP status")
            || message.contains("did not return content") || message.contains("timed out")
            || message.contains("TLS") || message.contains("SSL") || message.contains("DNS")) {
            return ArtifactImportFailure.FailureStage.REMOTE_ACCESS_FAILED;
        }
        return ArtifactImportFailure.FailureStage.DISCOVERY_CONTRACT_CHANGED;
    }

    private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    private record DocumentOutcome(
        AcquiredDocument document, FetchedDocument response, ProcessingResult processing, boolean parsed
    ) {}
    private record ContentPolicies(
        List<Pattern> responseRejectPatterns,
        List<Pattern> fingerprintIgnorePatterns
    ) {
        private static ContentPolicies from(RecruitmentSource source) {
            return new ContentPolicies(
                configuredPatterns(source, "responseRejectRegexes"),
                configuredPatterns(source, "contentFingerprintIgnoreRegexes"));
        }
    }
    private static final class SourceConfigurationException extends IllegalArgumentException {
        private SourceConfigurationException(String message) { super(message); }
        private SourceConfigurationException(String message, Throwable cause) { super(message, cause); }
    }
    private static final class YearCounts {
        int discovered, fetched, parsed, targetJobs, failed;
        void record(DocumentOutcome outcome, int newFailures) {
            failed += newFailures;
            if (outcome.response != null && outcome.document != null) fetched++;
            if (outcome.parsed) parsed++;
        }
    }
    private static final class Counts {
        int discovered, fetched, unchanged, added, updated, deactivated, failed, sourceFailures;
        boolean hasSuccess() { return fetched + unchanged + added + updated + deactivated > 0; }
    }
}
