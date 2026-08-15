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
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

public final class AcquisitionService {
    private static final Pattern URL_DATE = Pattern.compile("/(20\\d{2})/(\\d{1,2})/(\\d{1,2})/");
    private static final Pattern TITLE_YEAR = Pattern.compile("(20\\d{2})");
    private final AcquisitionStore store;
    private final SourceRunLock lock;
    private final SourceDiscoverer discoverer;
    private final DocumentFetcher fetcher;
    private final AttachmentDiscoverer attachments;
    private final AcquiredDocumentProcessor processor;
    private final ArtifactStore artifacts;
    private final NextRunCalculator nextRuns;
    private final AcquisitionObserver observer;
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
        this(store, lock, discoverer, fetcher, attachments, processor, artifacts, nextRuns,
            AcquisitionObserver.NOOP, clock, maxDocumentBytes);
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
        this.store=Objects.requireNonNull(store); this.lock=Objects.requireNonNull(lock);
        this.discoverer=Objects.requireNonNull(discoverer); this.fetcher=Objects.requireNonNull(fetcher);
        this.attachments=Objects.requireNonNull(attachments); this.processor=Objects.requireNonNull(processor);
        this.artifacts=Objects.requireNonNull(artifacts); this.nextRuns=Objects.requireNonNull(nextRuns);
        this.observer=Objects.requireNonNull(observer);
        this.clock=Objects.requireNonNull(clock);
        if (maxDocumentBytes < 1) throw new IllegalArgumentException("maxDocumentBytes must be positive");
        this.maxDocumentBytes=maxDocumentBytes;
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

    private SourceCrawlRun executeLocked(
        RecruitmentSource source, UUID runId, RunTrigger trigger, Instant started
    ) {
        store.saveRun(SourceCrawlRun.running(runId, source.id(), trigger, started));
        Counts counts = new Counts();
        try {
            FetchedDocument list = fetchTimed(source, request(source, listingUri(source), null));
            if (list.status() != 200 || list.content().length == 0) {
                throw new FetchFailedException("List page did not return content: " + list.status());
            }
            List<DiscoveredLink> details = discoverer.discover(source, list.finalUri(), list.content());
            counts.discovered = details.size();
            for (DiscoveredLink detail : details) {
                DocumentOutcome outcome = acquire(source, runId, detail, null, DocumentKind.ANNOUNCEMENT, counts);
                if (outcome.document == null) continue;
                List<DiscoveredLink> attachmentLinks;
                if (outcome.response != null && outcome.response.content().length > 0
                    && "text/html".equals(outcome.response.mediaType())) {
                    attachmentLinks = attachments.discover(source, detail.uri(), outcome.response.content());
                } else {
                    attachmentLinks = store.findDocuments(source.id()).stream()
                        .filter(document -> outcome.document.id().equals(document.parentDocumentId()))
                        .map(document -> new DiscoveredLink(document.canonicalUri(), detail.title()))
                        .toList();
                }
                counts.discovered += attachmentLinks.size();
                for (DiscoveredLink attachment : attachmentLinks) {
                    acquire(source, runId, attachment, outcome.document, DocumentKind.ATTACHMENT, counts);
                }
            }
            RunStatus status = counts.failed == 0 ? RunStatus.SUCCEEDED
                : counts.hasSuccess() ? RunStatus.PARTIALLY_SUCCEEDED : RunStatus.FAILED;
            SourceCrawlRun completed = terminal(runId, source.id(), trigger, status, started, counts,
                counts.failed == 0 ? null : "DOCUMENT_FAILURE",
                counts.failed == 0 ? null : counts.failed + " document(s) failed");
            updateSourceHealth(source, trigger, status);
            return store.saveRun(completed);
        } catch (RuntimeException failure) {
            counts.failed++;
            SourceCrawlRun failed = terminal(runId, source.id(), trigger, RunStatus.FAILED, started, counts,
                failure.getClass().getSimpleName(), safeMessage(failure));
            updateSourceHealth(source, trigger, RunStatus.FAILED);
            return store.saveRun(failed);
        }
    }

    private DocumentOutcome acquire(
        RecruitmentSource source,
        UUID runId,
        DiscoveredLink link,
        AcquiredDocument parent,
        DocumentKind kind,
        Counts counts
    ) {
        Optional<AcquiredDocument> prior = store.findDocument(source.id(), link.uri());
        FetchedDocument response;
        try {
            response = fetchTimed(source, request(source, link.uri(), prior.orElse(null)));
        } catch (RuntimeException failure) {
            counts.failed++;
            observer.document(source.code(), "FETCH_FAILED");
            return new DocumentOutcome(prior.orElse(null), null);
        }
        if (prior.isEmpty() && response.gone()) {
            counts.failed++;
            return new DocumentOutcome(null, response);
        }
        FetchObservation observation = observation(response, link.uri());
        DocumentTransition transition;
        try {
            transition = DocumentTransition.decide(prior.orElse(null), observation, clock.instant());
        } catch (RuntimeException failure) {
            counts.failed++;
            return new DocumentOutcome(prior.orElse(null), response);
        }
        AcquiredDocument document = transition.document();
        if (response.content().length > 0) {
            SourceArtifact artifact = artifacts.put(response.content(), response.mediaType(), clock.instant());
            document = transition.bind(source.id(), parent == null ? null : parent.id(), kind,
                URI.create(artifact.storageUri()));
        }
        ProcessingResult processing = null;
        if (transition.shouldProcess()) {
            try {
                byte[] content = response.content().length > 0 ? response.content() : readStored(document);
                processing = processor.process(processCommand(source, link, parent, document, content));
                if (processing.successful()) document = document.processed(document.contentFingerprint());
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
        counts.fetched++;
        observer.document(source.code(), transition.type().name());
        return new DocumentOutcome(document, response);
    }

    private FetchedDocument fetchTimed(RecruitmentSource source, FetchRequest request) {
        Instant started = clock.instant();
        try { return fetcher.fetch(request); }
        finally { observer.fetch(source.code(), Duration.between(started, clock.instant())); }
    }

    private FetchRequest request(RecruitmentSource source, URI uri, AcquiredDocument prior) {
        Set<String> hosts = new LinkedHashSet<>();
        hosts.add(source.baseUri().getHost());
        hosts.add(source.entryUri().getHost());
        Object configured = source.configuration().get("allowedHosts");
        if (configured instanceof Collection<?> values) values.stream().map(Object::toString).forEach(hosts::add);
        return new FetchRequest(uri, hosts, prior == null ? null : prior.etag(),
            prior == null ? null : prior.lastModified(), Duration.ofSeconds(20), maxDocumentBytes);
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
        AcquiredDocument document, byte[] content
    ) {
        URI announcementUri = parent == null ? document.canonicalUri() : parent.canonicalUri();
        LocalDate published = publishedDate(announcementUri);
        int year = published == null ? titleYear(link.title()).orElse(
            clock.instant().atZone(ZoneId.of(source.timeZone())).getYear()) : published.getYear();
        return new ProcessDocumentCommand(content, document.mediaType(), document.canonicalUri(), announcementUri,
            link.title(), clock.instant(), year, published, source.region(), EventType.PUBLIC_INSTITUTION);
    }

    private byte[] readStored(AcquiredDocument document) {
        var artifact = new SourceArtifact(UUID.nameUUIDFromBytes(document.contentFingerprint().getBytes(StandardCharsets.US_ASCII)),
            document.contentFingerprint(), document.mediaType(), 0, document.storageUri().toString(), clock.instant());
        try (var input = artifacts.open(artifact)) { return input.readAllBytes(); }
        catch (IOException exception) { throw new IllegalStateException("Could not reopen acquired artifact", exception); }
    }

    private void updateSourceHealth(RecruitmentSource source, RunTrigger trigger, RunStatus status) {
        Instant now = clock.instant();
        Instant next = trigger == RunTrigger.MANUAL ? source.nextDueAt() : nextRuns.next(source, now);
        store.saveSource(status == RunStatus.SUCCEEDED ? source.succeeded(now, next) : source.failed(now, next));
    }

    private SourceCrawlRun failedBeforeDiscovery(
        RecruitmentSource source, UUID runId, RunTrigger trigger, Instant started, RuntimeException failure
    ) {
        Counts counts = new Counts(); counts.failed = 1;
        SourceCrawlRun run = terminal(runId, source.id(), trigger, RunStatus.FAILED, started, counts,
            failure.getClass().getSimpleName(), safeMessage(failure));
        updateSourceHealth(source, trigger, RunStatus.FAILED);
        SourceCrawlRun saved = store.saveRun(run);
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
        return value.getMessage() == null ? value.getClass().getName() : value.getMessage();
    }

    private record DocumentOutcome(AcquiredDocument document, FetchedDocument response) {}
    private static final class Counts {
        int discovered, fetched, unchanged, added, updated, deactivated, failed;
        boolean hasSuccess() { return fetched + unchanged + added + updated + deactivated > 0; }
    }
}
