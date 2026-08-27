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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

public final class AcquisitionService {
    private static final Pattern URL_DATE = Pattern.compile("/(20\\d{2})/(\\d{1,2})/(\\d{1,2})/");
    private static final Pattern URL_ARTICLE_YEAR = Pattern.compile("/art/(20\\d{2})(?:/|$)");
    private static final Pattern TITLE_YEAR = Pattern.compile("(20\\d{2})");
    private static final Pattern LISTING_TOTAL = Pattern.compile("\\bcount\\s*=\\s*\"(\\d+)\"");
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
        this.listings=listings;
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
                DocumentOutcome outcome = acquire(source, runId, detail, null, DocumentKind.ANNOUNCEMENT,
                    detail.title(), counts);
                yearCounts.record(outcome, counts.failed - failuresBefore);
                if (outcome.document == null) continue;
                List<DiscoveredLink> attachmentLinks = discoverAttachments(source, detail, outcome);
                counts.discovered += attachmentLinks.size();
                yearCounts.discovered += attachmentLinks.size();
                for (DiscoveredLink attachment : attachmentLinks) {
                    failuresBefore = counts.failed;
                    DocumentOutcome attachmentOutcome = acquire(source, runId, attachment, outcome.document,
                        DocumentKind.ATTACHMENT,
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
                    .anyMatch(SourceYearCoverage::supportsAbsenceConclusion));
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
        if (listings != null) {
            return listings.read(source, new ListingQuery(recruitmentYears, true));
        }
        HistoricalListing legacy = historicalDetails(source);
        List<YearDiscoveredLink> links = legacy.details().stream()
            .map(link -> linkYear(link).map(year -> new YearDiscoveredLink(link, year)))
            .flatMap(Optional::stream)
            .filter(link -> recruitmentYears.contains(link.recruitmentYear()))
            .toList();
        Map<Integer, ListingEvidence> evidence = new LinkedHashMap<>();
        String basis = "official listing total=" + legacy.total()
            + "; traversed pages=" + legacy.pages() + "; pageSize=" + legacy.pageSize();
        for (int year : recruitmentYears) {
            int annual = (int) links.stream().filter(link -> link.recruitmentYear() == year).count();
            evidence.put(year, new ListingEvidence(legacy.pages(), legacy.total(), annual,
                Math.max(0, legacy.total() - links.size()), 0, null, null,
                true, "REPORTED_TOTAL_REACHED", basis));
        }
        return new ListingResult(links, evidence);
    }

    private HistoricalListing historicalDetails(RecruitmentSource source) {
        if (!"JCMS_PARAM_JSON".equals(source.configuration().get("historicalPaginationMode"))) {
            throw new IllegalArgumentException("Source does not declare verifiable historical pagination");
        }
        int pageSize = positiveConfiguration(source, "historicalPageSize");
        int maxPages = positiveConfiguration(source, "historicalMaxPages");
        LinkedHashMap<URI, DiscoveredLink> rawDistinct = new LinkedHashMap<>();
        LinkedHashMap<URI, DiscoveredLink> candidateDistinct = new LinkedHashMap<>();
        Set<String> pageFingerprints = new HashSet<>();
        Integer total = null;
        for (int page = 1; page <= maxPages; page++) {
            URI pageUri = listingPageUri(source, page, pageSize);
            FetchedDocument listing = fetchTimed(source, request(source, pageUri, null));
            if (listing.status() != 200 || listing.content().length == 0) {
                throw new FetchFailedException("Historical list page did not return content: " + listing.status());
            }
            int reported = listingTotal(listing.content());
            if (total == null) total = reported;
            else if (total != reported) throw new FetchFailedException("Historical listing total changed during traversal");
            String pageFingerprint = sha256(listing.content());
            if (!pageFingerprints.add(pageFingerprint) && (long) (page - 1) * pageSize < total) {
                throw new FetchFailedException("Historical listing repeated a non-terminal page");
            }
            int beforePage = rawDistinct.size();
            for (DiscoveredLink link : discoverer.discoverAll(source, listing.finalUri(), listing.content())) {
                rawDistinct.putIfAbsent(link.uri(), link);
            }
            for (DiscoveredLink link : discoverer.discover(source, listing.finalUri(), listing.content())) {
                candidateDistinct.putIfAbsent(link.uri(), link);
            }
            if (page > 1 && rawDistinct.size() == beforePage && beforePage < total) {
                throw new FetchFailedException("Historical listing page did not add any new entry");
            }
            if ((long) page * pageSize >= total) {
                if (rawDistinct.size() != total) {
                    throw new FetchFailedException("Historical listing unique entry count did not match reported total");
                }
                return new HistoricalListing(List.copyOf(candidateDistinct.values()), total, page, pageSize);
            }
        }
        throw new FetchFailedException("Historical listing exceeded configured page limit");
    }

    private static int positiveConfiguration(RecruitmentSource source, String key) {
        Object value = source.configuration().get(key);
        int parsed = value instanceof Number number ? number.intValue()
            : value == null ? -1 : Integer.parseInt(value.toString());
        if (parsed < 1) throw new IllegalArgumentException(key + " must be positive");
        return parsed;
    }

    private static URI listingPageUri(RecruitmentSource source, int page, int pageSize) {
        URI base = listingUri(source);
        String param = URLEncoder.encode(
            "{\"pageNo\":" + page + ",\"pageSize\":" + pageSize + "}", StandardCharsets.UTF_8);
        return URI.create(base + (base.getRawQuery() == null ? "?" : "&") + "paramJson=" + param);
    }

    private static int listingTotal(byte[] content) {
        String normalized = new String(content, StandardCharsets.UTF_8).replace("\\\"", "\"");
        var matcher = LISTING_TOTAL.matcher(normalized);
        if (!matcher.find()) throw new FetchFailedException("Historical listing does not report a total count");
        return Integer.parseInt(matcher.group(1));
    }

    private static Optional<Integer> linkYear(DiscoveredLink link) {
        LocalDate date = publishedDate(link.uri());
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
            List<DiscoveredLink> details = discoverIncremental(source);
            if (details.isEmpty()) {
                throw new FetchFailedException(
                    "Incremental listing yielded zero candidate announcements; discovery contract is unverified");
            }
            verifyCheckpoint(source.id(), Checkpoint.CONTRACT_VERIFIED,
                "incremental listing parsed candidates=" + details.size());
            counts.discovered = details.size();
            Map<Integer, YearCounts> byYear = new LinkedHashMap<>();
            for (DiscoveredLink detail : details) {
                YearCounts yearCounts = linkYear(detail)
                    .map(year -> byYear.computeIfAbsent(year, ignored -> new YearCounts()))
                    .orElse(null);
                if (yearCounts != null) yearCounts.discovered++;
                int failuresBefore = counts.failed;
                DocumentOutcome outcome = acquire(source, runId, detail, null, DocumentKind.ANNOUNCEMENT,
                    detail.title(), counts);
                if (yearCounts != null) yearCounts.record(outcome, counts.failed - failuresBefore);
                if (outcome.document == null) continue;
                List<DiscoveredLink> attachmentLinks = discoverAttachments(source, detail, outcome);
                counts.discovered += attachmentLinks.size();
                if (yearCounts != null) yearCounts.discovered += attachmentLinks.size();
                for (DiscoveredLink attachment : attachmentLinks) {
                    failuresBefore = counts.failed;
                    DocumentOutcome attachmentOutcome = acquire(source, runId, attachment, outcome.document,
                        DocumentKind.ATTACHMENT,
                        detail.title(), counts);
                    if (yearCounts != null) {
                        yearCounts.record(attachmentOutcome, counts.failed - failuresBefore);
                    }
                }
            }
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

    private List<DiscoveredLink> discoverIncremental(RecruitmentSource source) {
        if (source.configuration().containsKey("incrementalListingMaxPages")) {
            if (listings == null) {
                throw new IllegalStateException(
                    "SourceListingReader is required for bounded incremental listing traversal");
            }
            return listings.read(source, new ListingQuery(Set.of(), false)).links().stream()
                .map(YearDiscoveredLink::link)
                .toList();
        }
        FetchedDocument list = fetchTimed(source, request(source, listingUri(source), null));
        if (list.status() != 200 || list.content().length == 0) {
            throw new FetchFailedException("List page did not return content: " + list.status());
        }
        return discoverer.discover(source, list.finalUri(), list.content());
    }

    private void saveIncrementalCoverage(UUID sourceId, Map<Integer, YearCounts> byYear) {
        Instant now = clock.instant();
        for (var entry : byYear.entrySet()) {
            int year = entry.getKey();
            YearCounts values = entry.getValue();
            SourceYearCoverage previous = store.findSourceYearCoverage(sourceId, year).stream()
                .findFirst().orElse(null);
            if (previous != null && (previous.supportsAbsenceConclusion()
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
        UUID runId,
        DiscoveredLink link,
        AcquiredDocument parent,
        DocumentKind kind,
        String announcementTitle,
        Counts counts
    ) {
        Optional<AcquiredDocument> prior = store.findDocument(source.id(), link.uri());
        FetchedDocument response;
        try {
            response = fetchTimed(source, request(source, link.uri(), prior.orElse(null)));
        } catch (RuntimeException failure) {
            counts.failed++;
            counts.sourceFailures++;
            recordFailure(runId, source.id(), prior.map(AcquiredDocument::id).orElse(null),
                ArtifactImportFailure.FailureStage.ARTIFACT_DOWNLOAD_FAILED,
                failure.getClass().getSimpleName(), safeMessage(failure));
            observer.document(source.code(), "FETCH_FAILED");
            return new DocumentOutcome(prior.orElse(null), null, null, false);
        }
        if (prior.isEmpty() && response.gone()) {
            counts.failed++;
            counts.sourceFailures++;
            recordFailure(runId, source.id(), null,
                ArtifactImportFailure.FailureStage.ARTIFACT_DOWNLOAD_FAILED,
                "DOCUMENT_GONE", "Official document returned HTTP " + response.status());
            return new DocumentOutcome(null, response, null, false);
        }
        FetchObservation observation = observation(response, link.uri());
        DocumentTransition transition;
        try {
            transition = DocumentTransition.decide(prior.orElse(null), observation, clock.instant());
        } catch (RuntimeException failure) {
            counts.failed++;
            recordFailure(runId, source.id(), prior.map(AcquiredDocument::id).orElse(null),
                ArtifactImportFailure.FailureStage.DOCUMENT_PARSE_FAILED,
                failure.getClass().getSimpleName(), safeMessage(failure));
            return new DocumentOutcome(prior.orElse(null), response, null, false);
        }
        AcquiredDocument document = transition.document();
        if (response.content().length > 0) {
            SourceArtifact artifact = artifacts.put(response.content(), response.mediaType(), clock.instant());
            document = transition.bind(source.id(), parent == null ? null : parent.id(), kind,
                URI.create(artifact.storageUri()));
        }
        ProcessingResult processing = null;
        boolean processorChanged = !Objects.equals(document.lastProcessorVersion(), processor.version());
        if (transition.shouldProcess() || processorChanged) {
            try {
                byte[] content = response.content().length > 0 ? response.content() : readStored(document);
                processing = processor.process(processCommand(
                    source, link, parent, document, content, announcementTitle));
                if (processing.successful()) {
                    if (processing.status() == AcquiredDocumentProcessor.ProcessingStatus.PROCESSED_WITH_ERRORS) {
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
        ChangeType changeType = changeType(transition.type());
        if (changeType == null) {
            document = store.saveDocument(document);
            counts.unchanged++;
        } else {
            String current = changeType == ChangeType.DEACTIVATED
                ? AcquisitionChange.DEACTIVATED_FINGERPRINT : document.contentFingerprint();
            var change = new AcquisitionChange(UUID.randomUUID(), runId, source.id(), document.id(), changeType,
                transition.previousFingerprint(), current, document.canonicalUri(),
                processing == null ? Map.of() : processing.summary(), clock.instant());
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
        counts.fetched++;
        observer.document(source.code(), transition.type().name());
        boolean parsed = document.lastProcessedFingerprint() != null
            && document.lastProcessedFingerprint().equals(document.contentFingerprint());
        return new DocumentOutcome(document, response, processing, parsed);
    }

    private FetchedDocument fetchTimed(RecruitmentSource source, FetchRequest request) {
        Instant started = clock.instant();
        try { return fetcher.fetch(request); }
        finally { observer.fetch(source.code(), Duration.between(started, clock.instant())); }
    }

    private List<DiscoveredLink> discoverAttachments(
        RecruitmentSource source, DiscoveredLink detail, DocumentOutcome outcome
    ) {
        if (outcome.response != null && outcome.response.content().length > 0
            && isHtml(outcome.response.mediaType())) {
            return attachments.discover(source, detail.uri(), outcome.response.content());
        }
        if (isHtml(outcome.document.mediaType())) {
            return attachments.discover(source, detail.uri(), readStored(outcome.document));
        }
        return store.findDocuments(source.id()).stream()
            .filter(document -> outcome.document.id().equals(document.parentDocumentId()))
            .map(document -> new DiscoveredLink(document.canonicalUri(), detail.title()))
            .toList();
    }

    private static boolean isHtml(String mediaType) {
        return "text/html".equals(mediaType) || "application/xhtml+xml".equals(mediaType);
    }

    private FetchRequest request(RecruitmentSource source, URI uri, AcquiredDocument prior) {
        Set<String> hosts = new LinkedHashSet<>();
        hosts.add(source.baseUri().getHost());
        hosts.add(source.entryUri().getHost());
        Object configured = source.configuration().get("allowedHosts");
        if (configured instanceof Collection<?> values) values.stream().map(Object::toString).forEach(hosts::add);
        return new FetchRequest(uri, hosts, prior == null ? null : prior.etag(),
            prior == null ? null : prior.lastModified(), Duration.ofSeconds(20), maxDocumentBytes,
            source.id(), source.minimumRequestInterval());
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
        LocalDate published = publishedDate(announcementUri);
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

    private static FetchObservation observation(FetchedDocument response, URI stableUri) {
        if (response.notModified()) return FetchObservation.notModified(stableUri, response.etag(), response.lastModified());
        if (response.gone()) return FetchObservation.gone(stableUri, response.status());
        if (response.status() != 200) return FetchObservation.failure(stableUri, response.status());
        return FetchObservation.ok(stableUri, response.status(), sha256(response.content()),
            response.mediaType(), response.etag(), response.lastModified());
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
                .anyMatch(SourceYearCoverage::supportsAbsenceConclusion);
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

    private record HistoricalListing(List<DiscoveredLink> details, int total, int pages, int pageSize) {}
    private record DocumentOutcome(
        AcquiredDocument document, FetchedDocument response, ProcessingResult processing, boolean parsed
    ) {}
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
