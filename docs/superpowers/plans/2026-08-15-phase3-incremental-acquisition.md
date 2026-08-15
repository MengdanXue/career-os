# Phase 3A Incremental Acquisition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Poll two official Zhejiang/Hangzhou recruitment sources repeatedly, process only new or changed documents, and expose an auditable added/updated/deactivated feed.

**Architecture:** Add a framework-free acquisition domain and application orchestrator, then implement PostgreSQL, HTTP, HTML-discovery, routing, scheduling, and REST adapters in the existing infrastructure and web modules. Every source run is protected by a PostgreSQL advisory lock; document state and change records are durable, while existing Phase 2 services remain responsible for extraction, evidence, review, and stable-job deltas.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Data JPA, PostgreSQL 16, Flyway, Java HTTP Client, Jsoup, Apache POI, Micrometer, WireMock, Testcontainers, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-15-phase3-incremental-acquisition-design.md`

## Global Constraints

- The first enabled sources are exactly `ZJ_HRSS_INSTITUTION` and `HZ_HRSS_INSTITUTION`.
- Scheduled times use `Asia/Shanghai`; Zhejiang runs at `0 10 8 * * *`, Hangzhou at `0 20 8 * * *`.
- Static deterministic discovery is required; Playwright, login, CAPTCHA handling, and LLM link discovery are excluded.
- Listing-page disappearance, deadline passage, timeout, `429`, and `5xx` never imply deactivation.
- A detail URI deactivates only after two `404`/`410` results at least six hours apart, an explicit official cancellation, or an existing Phase 2 complete-snapshot job delta.
- Maximum response body is 25 MiB, connect timeout is five seconds, request timeout is twenty seconds, and redirects are limited to five and allowed hosts.
- Existing Flyway migrations V1-V5 are immutable. New schema is migration V6.
- Domain and application packages remain independent of Spring, JPA, and Spring AI.
- Every behavior change follows test-first red-green-refactor; the default suite never requires public internet.

---

### Task 1: Acquisition Domain and State Decisions

**Files:**
- Create: `career-domain/src/main/java/com/careeros/domain/acquisition/RecruitmentSource.java`
- Create: `career-domain/src/main/java/com/careeros/domain/acquisition/SourceCrawlRun.java`
- Create: `career-domain/src/main/java/com/careeros/domain/acquisition/AcquiredDocument.java`
- Create: `career-domain/src/main/java/com/careeros/domain/acquisition/AcquisitionChange.java`
- Create: `career-domain/src/main/java/com/careeros/domain/acquisition/DocumentTransition.java`
- Test: `career-domain/src/test/java/com/careeros/domain/acquisition/DocumentTransitionTest.java`

**Interfaces:**
- Produces: immutable domain records and `DocumentTransition decide(AcquiredDocument previous, FetchObservation observation, Instant now)`.
- Consumes: only JDK `URI`, `Instant`, `Duration`, `Map`, `List`, and `UUID` types.

- [ ] **Step 1: Write failing transition tests**

```java
@Test void firstSuccessfulFetchIsAdded() {
    var result = transitions.decide(null, FetchObservation.ok(uri, "a".repeat(64), "text/html"), NOW);
    assertThat(result.type()).isEqualTo(TransitionType.ADDED);
    assertThat(result.shouldProcess()).isTrue();
}

@Test void identicalFingerprintIsUnchangedAndNotProcessedAfterSuccess() {
    var prior = Fixtures.activeDocument("a".repeat(64), "a".repeat(64));
    var result = transitions.decide(prior, FetchObservation.ok(uri, "a".repeat(64), "text/html"), NOW);
    assertThat(result.type()).isEqualTo(TransitionType.UNCHANGED);
    assertThat(result.shouldProcess()).isFalse();
}

@Test void unchangedContentRetriesFailedProcessing() {
    var prior = Fixtures.activeDocument("a".repeat(64), null);
    assertThat(transitions.decide(prior, FetchObservation.notModified(uri), NOW).shouldProcess()).isTrue();
}

@Test void oneGoneObservationDoesNotDeactivate() {
    var prior = Fixtures.activeDocument("a".repeat(64), "a".repeat(64));
    var result = transitions.decide(prior, FetchObservation.gone(uri, 404), NOW);
    assertThat(result.type()).isEqualTo(TransitionType.UNCHANGED);
    assertThat(result.document().consecutiveGoneCount()).isEqualTo(1);
}

@Test void secondGoneObservationSixHoursLaterDeactivatesExactlyOnce() {
    var prior = Fixtures.goneOnceDocument(NOW.minus(Duration.ofHours(6)));
    var result = transitions.decide(prior, FetchObservation.gone(uri, 410), NOW);
    assertThat(result.type()).isEqualTo(TransitionType.DEACTIVATED);
    assertThat(result.document().state()).isEqualTo(DocumentState.DEACTIVATED);
}

@Test void timeoutAndServerFailureNeverDeactivate() {
    var prior = Fixtures.goneOnceDocument(NOW.minus(Duration.ofDays(1)));
    assertThat(transitions.decide(prior, FetchObservation.retryableFailure(uri, 503), NOW).type())
        .isEqualTo(TransitionType.NONE);
}

@Test void returningDocumentReactivatesAsUpdated() {
    var prior = Fixtures.deactivatedDocument("a".repeat(64));
    var result = transitions.decide(prior, FetchObservation.ok(uri, "b".repeat(64), "text/html"), NOW);
    assertThat(result.type()).isEqualTo(TransitionType.UPDATED);
    assertThat(result.document().state()).isEqualTo(DocumentState.ACTIVE);
}
```

- [ ] **Step 2: Run the domain test and verify RED**

Run: `mvn -q -pl career-domain -Dtest=DocumentTransitionTest test`

Expected: compilation fails because the acquisition records and transition service do not exist.

- [ ] **Step 3: Implement minimal framework-free records and transition rules**

```java
public enum TransitionType { ADDED, UPDATED, UNCHANGED, DEACTIVATED, NONE }
public enum DocumentState { ACTIVE, DEACTIVATED }
public enum DocumentKind { ANNOUNCEMENT, ATTACHMENT }

public record DocumentTransition(
    TransitionType type,
    AcquiredDocument document,
    boolean shouldProcess,
    String previousFingerprint,
    String currentFingerprint
) {
    public static DocumentTransition decide(
        AcquiredDocument previous, FetchObservation observation, Instant now) {
        Objects.requireNonNull(observation);
        Objects.requireNonNull(now);
        if (previous == null) {
            AcquiredDocument added = AcquiredDocument.firstSeen(observation, now);
            return new DocumentTransition(TransitionType.ADDED, added, true, null, added.contentFingerprint());
        }
        if (observation.retryableFailure()) {
            return new DocumentTransition(TransitionType.NONE, previous, false,
                previous.contentFingerprint(), previous.contentFingerprint());
        }
        if (observation.gone()) {
            AcquiredDocument gone = previous.recordGone(observation.status(), now);
            boolean deactivate = previous.state() == DocumentState.ACTIVE
                && gone.consecutiveGoneCount() >= 2
                && Duration.between(previous.lastGoneAt(), now).compareTo(Duration.ofHours(6)) >= 0;
            AcquiredDocument next = deactivate ? gone.deactivate(now) : gone;
            return new DocumentTransition(deactivate ? TransitionType.DEACTIVATED : TransitionType.UNCHANGED,
                next, false, previous.contentFingerprint(), next.contentFingerprint());
        }
        String fingerprint = observation.notModified()
            ? previous.contentFingerprint() : observation.contentFingerprint();
        boolean changed = !fingerprint.equals(previous.contentFingerprint())
            || previous.state() == DocumentState.DEACTIVATED;
        AcquiredDocument next = previous.recordSuccess(observation, fingerprint, now);
        boolean retryProcessing = !fingerprint.equals(previous.lastProcessedFingerprint());
        return new DocumentTransition(changed ? TransitionType.UPDATED : TransitionType.UNCHANGED,
            next, changed || retryProcessing, previous.contentFingerprint(), fingerprint);
    }
}
```

Define `RecruitmentSource`, `SourceCrawlRun`, `AcquiredDocument`, and `AcquisitionChange` with the exact fields and enums from sections 5.1-5.4 of the design. Constructors defensively copy maps/lists and reject blank codes, negative counters, invalid fingerprints, and terminal runs without `completedAt`.

- [ ] **Step 4: Run domain tests and architecture tests**

Run: `mvn -q -pl career-domain -Dtest=DocumentTransitionTest test`

Expected: PASS.

Run: `mvn -q -pl career-web -am -Dtest=ArchitectureTest test`

Expected: PASS; domain acquisition classes have no framework dependencies.

- [ ] **Step 5: Commit**

```bash
git add career-domain/src/main/java/com/careeros/domain/acquisition career-domain/src/test/java/com/careeros/domain/acquisition
git commit -m "feat: define acquisition state transitions"
```

---

### Task 2: V6 Schema and PostgreSQL Persistence

**Files:**
- Create: `career-infrastructure/src/main/resources/db/migration/V6__incremental_acquisition.sql`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/AcquisitionJpaModels.java`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/RecruitmentSourceJpaRepository.java`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/SourceCrawlRunJpaRepository.java`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/AcquiredDocumentJpaRepository.java`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/AcquisitionChangeJpaRepository.java`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/JpaAcquisitionStore.java`
- Create: `career-application/src/main/java/com/careeros/application/AcquisitionPorts.java`
- Modify: `career-infrastructure/src/test/java/com/careeros/infrastructure/MigrationIntegrationTest.java`
- Test: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/JpaAcquisitionStoreTest.java`

**Interfaces:**
- Produces: `AcquisitionPorts.AcquisitionStore` with source/run/document/change operations.
- Consumes: Task 1 domain records.

- [ ] **Step 1: Extend the migration test first**

```java
assertThat(result.migrationsExecuted).isEqualTo(6);
assertThat(countTables(connection,
    "recruitment_source", "source_crawl_run", "acquired_document", "acquisition_change"))
    .isEqualTo(4);
assertThat(queryInt(connection,
    "select count(*) from recruitment_source where enabled and code in " +
    "('ZJ_HRSS_INSTITUTION','HZ_HRSS_INSTITUTION')"))
    .isEqualTo(2);
```

Add a test that migrates a separate schema only through V5, inserts a legacy extraction/job row, migrates to V6, and verifies that the row remains unchanged.

- [ ] **Step 2: Verify migration RED**

Run: `mvn -q -pl career-infrastructure -Dtest=MigrationIntegrationTest test`

Expected: FAIL because only five migrations run and the four tables do not exist.

- [ ] **Step 3: Add V6 tables, constraints, indexes, and deterministic seeds**

The migration must contain concrete constraints equivalent to:

```sql
CREATE TABLE recruitment_source (
    id UUID PRIMARY KEY,
    code VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    base_uri TEXT NOT NULL,
    entry_uri TEXT NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    region VARCHAR(100) NOT NULL,
    crawl_mode VARCHAR(30) NOT NULL CHECK (crawl_mode IN ('STATIC_HTML','PLAYWRIGHT')),
    enabled BOOLEAN NOT NULL,
    cron_expression VARCHAR(80) NOT NULL,
    time_zone VARCHAR(80) NOT NULL,
    minimum_request_interval_ms BIGINT NOT NULL CHECK (minimum_request_interval_ms >= 0),
    configuration JSONB NOT NULL CHECK (jsonb_typeof(configuration) = 'object'),
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    next_due_at TIMESTAMPTZ,
    consecutive_failure_count INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failure_count >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
```

Add the remaining three tables with all design fields, foreign keys, enum checks, 64-character fingerprint checks, non-negative counters, `(source_id, canonical_uri)` uniqueness, and `(document_id, change_type, current_fingerprint)` uniqueness. Seed UUIDs `01992f09-0000-7000-8000-000000000301` and `01992f09-0000-7000-8000-000000000302`.

- [ ] **Step 4: Write persistence-port and store tests**

```java
@Test void savesAndFindsDocumentBySourceAndCanonicalUri() {
    AcquiredDocument saved = store.saveDocument(document);
    assertThat(store.findDocument(source.id(), document.canonicalUri())).contains(saved);
}

@Test void appendsOneChangeForOneDocumentFingerprint() {
    store.appendChange(change);
    assertThatThrownBy(() -> store.appendChange(changeWithDifferentIdButSameNaturalKey()))
        .isInstanceOf(DuplicateKeyException.class);
}

@Test void readsChangesAfterTimestampAndIdCursorInStableOrder() {
    store.appendChange(changeAt(NOW, UUID.fromString("00000000-0000-0000-0000-000000000001")));
    store.appendChange(changeAt(NOW, UUID.fromString("00000000-0000-0000-0000-000000000002")));
    ChangePage first = store.findChanges(null, source.id(), Set.of(), 1);
    ChangePage second = store.findChanges(first.nextCursor(), source.id(), Set.of(), 1);
    assertThat(Stream.concat(first.items().stream(), second.items().stream()).map(AcquisitionChange::id))
        .containsExactly(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"));
}

@Test void findsOnlyEnabledDueSources() {
    saveSource(enabledDueSource());
    saveSource(disabledDueSource());
    saveSource(enabledFutureSource());
    assertThat(store.findDueSources(NOW, 10)).extracting(RecruitmentSource::code)
        .containsExactly("ZJ_HRSS_INSTITUTION");
}
```

Define the application port explicitly:

```java
public interface AcquisitionStore {
    List<RecruitmentSource> findDueSources(Instant now, int limit);
    List<RecruitmentSource> findSources();
    RecruitmentSource findSource(UUID id);
    RecruitmentSource saveSource(RecruitmentSource source);
    SourceCrawlRun saveRun(SourceCrawlRun run);
    SourceCrawlRun findRun(UUID id);
    Optional<AcquiredDocument> findDocument(UUID sourceId, URI canonicalUri);
    AcquiredDocument saveDocument(AcquiredDocument document);
    AcquisitionChange appendChange(AcquisitionChange change);
    ChangePage findChanges(ChangeCursor cursor, UUID sourceId, Set<ChangeType> types, int size);
}
```

- [ ] **Step 5: Implement JPA mappings and store adapter**

Use focused JPA entity classes in `AcquisitionJpaModels`; do not add acquisition fields to the existing `JpaModels` file. Use `@Version` on acquired documents and Spring Data queries for due sources, canonical URIs, and cursor ordering.

- [ ] **Step 6: Verify persistence GREEN**

Run: `mvn -q -pl career-infrastructure -Dtest=MigrationIntegrationTest,JpaAcquisitionStoreTest test`

Expected: PASS with PostgreSQL Testcontainers.

- [ ] **Step 7: Commit**

```bash
git add career-application/src/main/java/com/careeros/application/AcquisitionPorts.java career-infrastructure/src/main/resources/db/migration/V6__incremental_acquisition.sql career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition career-infrastructure/src/test
git commit -m "feat: persist acquisition sources and changes"
```

---

### Task 3: URI Canonicalization and Deterministic Link Discovery

**Files:**
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/CanonicalUri.java`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/StaticHtmlSourceDiscoverer.java`
- Test: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/CanonicalUriTest.java`
- Test: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/StaticHtmlSourceDiscovererTest.java`
- Create: `career-infrastructure/src/test/resources/fixtures/acquisition/zj-list.html`
- Create: `career-infrastructure/src/test/resources/fixtures/acquisition/hz-list.html`

**Interfaces:**
- Produces: `URI CanonicalUri.normalize(URI)` and `List<DiscoveredLink> SourceDiscoverer.discover(RecruitmentSource, URI pageUri, byte[] html)`.
- Consumes: source configuration JSON and official-page fixtures.

- [ ] **Step 1: Write canonicalization tests**

```java
@ParameterizedTest
@CsvSource({
  "HTTPS://HRSS.HANGZHOU.GOV.CN:443/a#top,https://hrss.hangzhou.gov.cn/a",
  "https://host/path?b=2&a=1&utm_source=x,https://host/path?a=1&b=2",
  "https://host,https://host/"
})
void canonicalizesWithoutDroppingBusinessParameters(String raw, String expected) {
    assertThat(CanonicalUri.normalize(URI.create(raw))).hasToString(expected);
}
```

- [ ] **Step 2: Write discovery tests with captured fixtures**

```java
@Test void findsOnlyOfficialRecruitmentArticles() {
    var links = discoverer.discover(source, source.entryUri(), fixture("zj-list.html"));
    assertThat(links).extracting(link -> link.uri().toString())
        .containsExactly("https://rlsbt.zj.gov.cn/art/2026/3/17/art_1229743683_58950000.html");
}

@Test void rejectsExternalRedirectTargetsAndExcludedTitles() {
    assertThat(links).noneMatch(link -> link.uri().getHost().equals("example.com"));
    assertThat(links).noneMatch(link -> link.title().contains("拟聘"));
}
```

- [ ] **Step 3: Run and verify RED**

Run: `mvn -q -pl career-infrastructure -Dtest=CanonicalUriTest,StaticHtmlSourceDiscovererTest test`

Expected: compilation fails because both components are absent.

- [ ] **Step 4: Implement the tested normalizer and Jsoup discoverer**

The discoverer resolves relative links against `pageUri`, canonicalizes them, rejects non-HTTPS and non-allowlisted hosts, applies `articleUrlRegex`, then applies title include/exclude regexes. Return a distinct, URI-sorted immutable list.

- [ ] **Step 5: Run tests and commit**

Run: `mvn -q -pl career-infrastructure -Dtest=CanonicalUriTest,StaticHtmlSourceDiscovererTest test`

Expected: PASS.

```bash
git add career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition career-infrastructure/src/test/resources/fixtures/acquisition
git commit -m "feat: discover canonical official links"
```

---

### Task 4: Safe Conditional HTTP Fetching and Media Detection

**Files:**
- Create: `career-application/src/main/java/com/careeros/application/AcquisitionHttpPorts.java`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/JavaHttpDocumentFetcher.java`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/MediaTypeDetector.java`
- Test: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/JavaHttpDocumentFetcherTest.java`
- Test: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/MediaTypeDetectorTest.java`

**Interfaces:**
- Produces: `FetchedDocument fetch(FetchRequest request)` and `String detect(URI uri, String header, byte[] content)`.
- Consumes: `java.net.http.HttpClient`, request validators, size/time/redirect policy.

- [ ] **Step 1: Write WireMock behavior tests**

```java
@Test void sendsValidatorsAndReturnsNotModifiedWithoutBody() {
    stubNotModified("/notice", "etag-1", "Wed, 12 Aug 2026 08:00:00 GMT");
    FetchedDocument result = fetcher.fetch(request("/notice", "etag-1", "Wed, 12 Aug 2026 08:00:00 GMT"));
    assertThat(result.notModified()).isTrue();
    verify(getRequestedFor(urlEqualTo("/notice")).withHeader("If-None-Match", equalTo("etag-1")));
}

@Test void rejectsRedirectToUnapprovedHost() {
    stubRedirect("/notice", "https://external.example/file.pdf");
    assertThatThrownBy(() -> fetcher.fetch(request("/notice")))
        .isInstanceOf(FetchRejectedException.class)
        .hasMessageContaining("external.example");
}

@Test void stopsReadingAboveTwentyFiveMebibytes() {
    stubBody("/large", new byte[26_214_401]);
    assertThatThrownBy(() -> fetcher.fetch(request("/large")))
        .isInstanceOf(DocumentTooLargeException.class);
}

@Test void retriesFiveHundredThreeTwiceButDoesNotRetryFourHundred() {
    stubStatus("/retry", 503);
    assertThatThrownBy(() -> fetcher.fetch(request("/retry"))).isInstanceOf(FetchFailedException.class);
    verify(3, getRequestedFor(urlEqualTo("/retry")));
    stubStatus("/bad", 400);
    assertThatThrownBy(() -> fetcher.fetch(request("/bad"))).isInstanceOf(FetchRejectedException.class);
    verify(1, getRequestedFor(urlEqualTo("/bad")));
}

@Test void honorsRetryAfterFor429WithInjectedSleeper() {
    stubRetryAfterThenOk("/limited", 2);
    fetcher.fetch(request("/limited"));
    assertThat(sleeper.requestedDelays()).contains(Duration.ofSeconds(2));
}
```

Define the request/result records:

```java
public record FetchRequest(
    URI uri, Set<String> allowedHosts, String etag, String lastModified,
    Duration connectTimeout, Duration requestTimeout, long maxBytes
) {}

public record FetchedDocument(
    URI finalUri, int status, String mediaType, byte[] content,
    String etag, String lastModified
) {
    public boolean notModified() { return status == 304; }
    public boolean gone() { return status == 404 || status == 410; }
}
```

- [ ] **Step 2: Write magic-byte media tests**

Assert `%PDF-` -> `application/pdf`, ZIP containing `[Content_Types].xml` and `xl/workbook.xml` -> XLSX, OLE2 header -> XLS, `<html` -> HTML, and an HTML body with `.xlsx` filename -> HTML rather than XLSX.

- [ ] **Step 3: Run and verify RED**

Run: `mvn -q -pl career-infrastructure -Dtest=JavaHttpDocumentFetcherTest,MediaTypeDetectorTest test`

Expected: compilation fails for missing ports/adapters.

- [ ] **Step 4: Implement HTTP adapter and detector**

Use an injected `HttpClient`, `Clock`, and `Sleeper` for deterministic tests. Build the production client with five-second connect timeout and redirect handling implemented explicitly so each redirect host is validated. Stream through a counting input stream and abort at `26_214_400` bytes.

- [ ] **Step 5: Run tests and commit**

Run: `mvn -q -pl career-infrastructure -Dtest=JavaHttpDocumentFetcherTest,MediaTypeDetectorTest test`

Expected: PASS.

```bash
git add career-application/src/main/java/com/careeros/application/AcquisitionHttpPorts.java career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition
git commit -m "feat: fetch official documents safely"
```

---

### Task 5: Route HTML, PDF, and Official Workbooks

**Files:**
- Create: `career-application/src/main/java/com/careeros/application/AcquiredDocumentProcessor.java`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/Phase2DocumentProcessor.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/OfficialExcelImportService.java`
- Test: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/Phase2DocumentProcessorTest.java`

**Interfaces:**
- Produces: `ProcessingResult process(ProcessDocumentCommand command)`.
- Consumes: `ExtractionService`, `OfficialExcelImportService`, and acquisition artifact metadata.

- [ ] **Step 1: Write routing tests**

```java
@ParameterizedTest @ValueSource(strings = {"text/html", "application/pdf"})
void htmlAndPdfUseExtractionService(String mediaType) {
    ProcessingResult result = processor.process(command(mediaType));
    assertThat(result.status()).isEqualTo(ProcessingStatus.PROCESSED);
    assertThat(result.extractionRunId()).isNotNull();
    verify(extractionService).submit(any(SubmitExtractionCommand.class));
}

@ParameterizedTest @ValueSource(strings = {
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
})
void xlsAndXlsxUseOfficialExcelImport(String mediaType) {
    ProcessingResult result = processor.process(command(mediaType));
    assertThat(result.inserted()).isEqualTo(1);
    verify(excelImport).importWorkbook(any(InputStream.class), any(ImportCommand.class));
}
@Test void unsupportedMediaReturnsFailureWithoutInventedJobs() {
    assertThat(processor.process(command("application/zip"))).isEqualTo(ProcessingResult.unsupported());
}
@Test void importUsesParentAnnouncementUriAsStableEventSource() {
    processor.process(commandForAttachment(
        URI.create("https://host/files/jobs-v2.xlsx"),
        URI.create("https://host/art/2026/1/1/notice.html")));
    verify(excelImport).importWorkbook(any(InputStream.class), argThat(command ->
        command.sourceUrl().equals("https://host/art/2026/1/1/notice.html")));
}
```

The result contract is:

```java
public record ProcessingResult(
    ProcessingStatus status,
    UUID extractionRunId,
    UUID recruitmentEventId,
    int inserted,
    int updated,
    int unchanged,
    int deactivated,
    List<String> stableJobKeys,
    String errorCode
) {}
```

- [ ] **Step 2: Run and verify RED**

Run: `mvn -q -pl career-infrastructure -Dtest=Phase2DocumentProcessorTest test`

Expected: compilation fails because the processor contract and adapter do not exist.

- [ ] **Step 3: Add a byte-array entry point to Excel import and implement routing**

Keep the existing `importWorkbook(InputStream, ImportCommand)` API. Add only a metadata-preserving wrapper if needed; do not copy spreadsheet parsing into acquisition code. Derive the import year and title from parent announcement metadata, and use the parent announcement canonical URI for `ImportCommand.sourceUrl()`.

- [ ] **Step 4: Verify GREEN and existing importer regression tests**

Run: `mvn -q -pl career-infrastructure -Dtest=Phase2DocumentProcessorTest,DefaultJobUpsertServiceTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add career-application/src/main/java/com/careeros/application/AcquiredDocumentProcessor.java career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/Phase2DocumentProcessor.java career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/OfficialExcelImportService.java career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/Phase2DocumentProcessorTest.java
git commit -m "feat: route acquired official documents"
```

---

### Task 6: Acquisition Orchestrator and Attachment Flow

**Files:**
- Create: `career-application/src/main/java/com/careeros/application/AcquisitionService.java`
- Create: `career-application/src/test/java/com/careeros/application/AcquisitionServiceTest.java`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/HtmlAttachmentDiscoverer.java`
- Test: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/HtmlAttachmentDiscovererTest.java`

**Interfaces:**
- Produces: `SourceCrawlRun run(UUID sourceId, RunTrigger trigger)`.
- Consumes: stores from Task 2, discovery from Task 3, fetcher from Task 4, processor from Task 5, `Clock`, and a source lock port.

- [ ] **Step 1: Write application-service tests with in-memory fakes**

```java
@Test void firstRunEmitsAddedAndSecondIdenticalRunEmitsNothing() {
    service.run(SOURCE_ID, RunTrigger.MANUAL);
    service.run(SOURCE_ID, RunTrigger.MANUAL);
    assertThat(store.changes()).extracting(AcquisitionChange::changeType)
        .containsExactly(ChangeType.ADDED);
    assertThat(processor.commands()).hasSize(1);
}

@Test void changedBodyEmitsOneUpdatedAndProcessesOnlyThatBody() {
    service.run(SOURCE_ID, RunTrigger.MANUAL);
    fetcher.replaceDetailBody("changed".getBytes(UTF_8));
    service.run(SOURCE_ID, RunTrigger.MANUAL);
    assertThat(store.changes()).extracting(AcquisitionChange::changeType)
        .containsExactly(ChangeType.ADDED, ChangeType.UPDATED);
    assertThat(processor.commands()).hasSize(2);
}

@Test void failedProcessingRetriesOnNextUnchangedFetchWithoutDuplicateChange() {
    processor.failNext();
    service.run(SOURCE_ID, RunTrigger.MANUAL);
    service.run(SOURCE_ID, RunTrigger.MANUAL);
    assertThat(store.changes()).hasSize(1);
    assertThat(processor.commands()).hasSize(2);
}

@Test void oneDetailFailureMakesPartialRunAndContinuesOtherDetails() {
    fetcher.failDetail("/broken", 500);
    SourceCrawlRun run = service.run(SOURCE_ID, RunTrigger.MANUAL);
    assertThat(run.status()).isEqualTo(RunStatus.PARTIALLY_SUCCEEDED);
    assertThat(run.failedCount()).isEqualTo(1);
    assertThat(run.fetchedCount()).isEqualTo(1);
}

@Test void listFailureMakesRunFailedAndDoesNotTouchKnownDocuments() {
    fetcher.failList(503);
    SourceCrawlRun run = service.run(SOURCE_ID, RunTrigger.MANUAL);
    assertThat(run.status()).isEqualTo(RunStatus.FAILED);
    assertThat(store.documents()).isEmpty();
}

@Test void attachmentsAreChildrenAndUseTheSameDeltaRules() {
    service.run(SOURCE_ID, RunTrigger.MANUAL);
    AcquiredDocument attachment = store.documents().stream()
        .filter(document -> document.kind() == DocumentKind.ATTACHMENT).findFirst().orElseThrow();
    assertThat(attachment.parentDocumentId()).isNotNull();
}
```

- [ ] **Step 2: Run and verify RED**

Run: `mvn -q -pl career-application -Dtest=AcquisitionServiceTest test`

Expected: compilation fails because `AcquisitionService` is absent.

- [ ] **Step 3: Implement minimal orchestration**

Constructor dependencies must be explicit:

```java
public AcquisitionService(
    AcquisitionStore store,
    SourceRunLock lock,
    SourceDiscoverer discoverer,
    DocumentFetcher fetcher,
    AttachmentDiscoverer attachments,
    AcquiredDocumentProcessor processor,
    ArtifactStore artifacts,
    Clock clock
) {
    this.store = Objects.requireNonNull(store);
    this.lock = Objects.requireNonNull(lock);
    this.discoverer = Objects.requireNonNull(discoverer);
    this.fetcher = Objects.requireNonNull(fetcher);
    this.attachments = Objects.requireNonNull(attachments);
    this.processor = Objects.requireNonNull(processor);
    this.artifacts = Objects.requireNonNull(artifacts);
    this.clock = Objects.requireNonNull(clock);
}

public SourceCrawlRun run(UUID sourceId, RunTrigger trigger) {
    RecruitmentSource source = store.findSource(sourceId);
    return lock.tryExecute(source.code(), Duration.ofSeconds(2), () -> executeRun(source, trigger))
        .orElseGet(() -> skippedLockedRun(source, trigger));
}
```

Keep per-document processing in a private method returning a concrete counter/result value. Persist document state and its `AcquisitionChange` in one store transaction. On processor failure, save `contentFingerprint`, leave `lastProcessedFingerprint` unchanged, record failure counters, and continue.

- [ ] **Step 4: Implement and test attachment discovery**

Use Jsoup on detail HTML, the source's `attachmentSelector`, allowed hosts, canonicalization, and suffix/magic detection. Return only HTML/PDF/XLS/XLSX candidates; de-duplicate by canonical URI.

- [ ] **Step 5: Verify application and infrastructure tests**

Run: `mvn -q -pl career-application -Dtest=AcquisitionServiceTest test`

Run: `mvn -q -pl career-infrastructure -Dtest=HtmlAttachmentDiscovererTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add career-application/src/main/java/com/careeros/application/AcquisitionService.java career-application/src/test/java/com/careeros/application/AcquisitionServiceTest.java career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/HtmlAttachmentDiscoverer.java career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/HtmlAttachmentDiscovererTest.java
git commit -m "feat: orchestrate incremental source acquisition"
```

---

### Task 7: Distributed Source Lock and Scheduler

**Files:**
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/PostgresSourceRunLock.java`
- Create: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/PostgresSourceRunLockTest.java`
- Create: `career-web/src/main/java/com/careeros/AcquisitionScheduler.java`
- Modify: `career-web/src/main/java/com/careeros/CareerOsApplication.java`
- Modify: `career-web/src/main/java/com/careeros/ApplicationConfiguration.java`
- Modify: `career-web/src/main/resources/application.yml`
- Test: `career-web/src/test/java/com/careeros/AcquisitionSchedulerTest.java`

**Interfaces:**
- Produces: `Optional<T> SourceRunLock.tryExecute(String sourceCode, Duration wait, Supplier<T> work)` and a once-per-minute due-source dispatcher.
- Consumes: PostgreSQL session advisory locks and `AcquisitionService`.

- [ ] **Step 1: Write two-context lock integration test**

Start two `PostgresSourceRunLock` instances using the same Testcontainer database. Hold the first lock, assert the second returns `Optional.empty()` within two seconds, then release and assert the second can acquire it.

- [ ] **Step 2: Write scheduler test**

```java
@Test void dispatchesEachDueEnabledSourceOnce() {
    scheduler.dispatchDueSources();
    assertThat(service.requestedSourceIds()).containsExactly(ZJ_ID, HZ_ID);
}

@Test void manualRunsDoNotMoveScheduledNextDueAt() {
    Instant before = store.findSource(ZJ_ID).nextDueAt();
    service.run(ZJ_ID, RunTrigger.MANUAL);
    assertThat(store.findSource(ZJ_ID).nextDueAt()).isEqualTo(before);
}
```

- [ ] **Step 3: Run and verify RED**

Run: `mvn -q -pl career-infrastructure -Dtest=PostgresSourceRunLockTest test`

Run: `mvn -q -pl career-web -am -Dtest=AcquisitionSchedulerTest test`

Expected: compilation fails for missing lock and scheduler.

- [ ] **Step 4: Implement lock with a dedicated bounded Hikari pool**

Use `pg_try_advisory_lock(hashtextextended(sourceCode, 3))` in a polling loop capped at two seconds, keep the same session for work, and always call `pg_advisory_unlock`. Configure pool size `2`, connection timeout `5_000 ms`, and close it with `@PreDestroy`.

- [ ] **Step 5: Enable scheduling and wire beans**

Add `@EnableScheduling` to the application, `@Scheduled(fixedDelayString = "${career-os.acquisition.dispatch-delay-ms:60000}")`, and configuration for HTTP contact, limits, lock pool, and `live-smoke-enabled: false`. Scheduler exceptions are logged per source and never stop the dispatcher thread.

- [ ] **Step 6: Verify GREEN and commit**

Run the two commands from Step 3 and expect PASS.

```bash
git add career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/PostgresSourceRunLock.java career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/PostgresSourceRunLockTest.java career-web/src/main/java/com/careeros/AcquisitionScheduler.java career-web/src/main/java/com/careeros/CareerOsApplication.java career-web/src/main/java/com/careeros/ApplicationConfiguration.java career-web/src/main/resources/application.yml career-web/src/test/java/com/careeros/AcquisitionSchedulerTest.java
git commit -m "feat: schedule acquisition with distributed locking"
```

---

### Task 8: Operations API, Cursor Feed, and Metrics

**Files:**
- Create: `career-web/src/main/java/com/careeros/AcquisitionApiModels.java`
- Create: `career-web/src/main/java/com/careeros/AcquisitionController.java`
- Create: `career-web/src/main/java/com/careeros/AcquisitionMetrics.java`
- Test: `career-web/src/test/java/com/careeros/AcquisitionApiTest.java`
- Test: `career-web/src/test/java/com/careeros/AcquisitionMetricsTest.java`
- Modify: `career-web/src/main/java/com/careeros/ApiExceptionHandler.java`

**Interfaces:**
- Produces: the source, run, and change endpoints from design section 10.
- Consumes: `AcquisitionService`, `AcquisitionStore`, and Micrometer `MeterRegistry`.

- [ ] **Step 1: Write MockMvc API tests**

```java
@Test void listsSourcesWithoutExposingConfigurationSecrets() throws Exception {
    mvc.perform(get("/api/acquisition/sources"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("ZJ_HRSS_INSTITUTION"))
        .andExpect(jsonPath("$[0].configuration").doesNotExist());
}

@Test void manualTriggerReturnsAcceptedRunLocation() throws Exception {
    mvc.perform(post("/api/acquisition/sources/{id}/runs", ZJ_ID))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Location", matchesPattern("/api/acquisition/runs/[0-9a-f-]+")));
}

@Test void returnsRunByIdAndFiltersRunHistory() throws Exception {
    mvc.perform(get("/api/acquisition/runs").param("sourceId", ZJ_ID.toString()).param("status", "SUCCEEDED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].status", everyItem(is("SUCCEEDED"))));
}

@Test void changeCursorDoesNotSkipEqualTimestamps() throws Exception {
    String firstCursor = readNextCursor(mvc.perform(get("/api/acquisition/changes").param("size", "1")));
    mvc.perform(get("/api/acquisition/changes").param("size", "1").param("cursor", firstCursor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", hasSize(1)));
}

@Test void capsPageSizeAtTwoHundredAndRejectsInvalidCursor() throws Exception {
    mvc.perform(get("/api/acquisition/changes").param("size", "201"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/acquisition/changes").param("cursor", "not-base64"))
        .andExpect(status().isBadRequest());
}
```

Cursor encoding is URL-safe Base64 without padding of UTF-8 text `<instant>|<uuid>`. Decode strictly into both components and reject trailing data.

- [ ] **Step 2: Write metrics tests**

Use `SimpleMeterRegistry`; record one success, partial failure, document update, processing failure, and lock skip, then assert the exact metric names and tag values from design section 12.

- [ ] **Step 3: Run and verify RED**

Run: `mvn -q -pl career-web -am -Dtest=AcquisitionApiTest,AcquisitionMetricsTest test`

Expected: compilation fails because controller/models/metrics are absent.

- [ ] **Step 4: Implement API DTOs, controller, cursor codec, and observer**

Use `/api/acquisition` exactly. Do not return JPA entities. Return `ProblemDetail` with status 400 for invalid filters/cursors, 404 for unknown source/run IDs, and 409 only for optimistic concurrency conflicts; locked runs are normal `SKIPPED_LOCKED` run responses.

- [ ] **Step 5: Verify GREEN and OpenAPI exposure**

Run: `mvn -q -pl career-web -am -Dtest=AcquisitionApiTest,AcquisitionMetricsTest,CareerOsApplicationTest test`

Expected: PASS and `/v3/api-docs` includes all five acquisition operations.

- [ ] **Step 6: Commit**

```bash
git add career-web/src/main/java/com/careeros/AcquisitionApiModels.java career-web/src/main/java/com/careeros/AcquisitionController.java career-web/src/main/java/com/careeros/AcquisitionMetrics.java career-web/src/main/java/com/careeros/ApiExceptionHandler.java career-web/src/test/java/com/careeros/AcquisitionApiTest.java career-web/src/test/java/com/careeros/AcquisitionMetricsTest.java
git commit -m "feat: expose acquisition operations and change feed"
```

---

### Task 9: End-to-End Fixtures, Live Smoke Profile, and Documentation

**Files:**
- Create: `career-web/src/test/java/com/careeros/AcquisitionEndToEndTest.java`
- Create: `career-web/src/test/java/com/careeros/OfficialSourceLiveSmokeTest.java`
- Create: `career-web/src/test/resources/fixtures/acquisition/zj-detail.html`
- Create: `career-web/src/test/resources/fixtures/acquisition/hz-detail.html`
- Create: `career-web/src/test/resources/fixtures/acquisition/jobs.xlsx`
- Modify: `pom.xml`
- Modify: `README.md`
- Create: `docs/PHASE3_API.md`

**Interfaces:**
- Produces: repeatable full-stack acceptance evidence and opt-in live-source compatibility checks.
- Consumes: all prior tasks.

- [ ] **Step 1: Write full-stack failing acceptance test**

Use PostgreSQL Testcontainers plus WireMock. Test this exact sequence:

```java
runSource(ZJ_ID);                         // HTML detail + XLSX attachment
assertChangeCounts(2, 0, 0);
assertJobCounts(1, 0, 0, 0);

runSource(ZJ_ID);                         // identical bodies / 304
assertChangeCounts(2, 0, 0);
assertNoAdditionalExtractionOrJobs();

changeWorkbookFixture();
runSource(ZJ_ID);
assertChangeCounts(2, 1, 0);
assertJobCounts(1, 1, 0, 0);

returnGoneTwiceSixHoursApart();
assertChangeCounts(2, 1, 1);
```

Also assert that a failing attachment produces `PARTIALLY_SUCCEEDED` while a second detail still completes.

- [ ] **Step 2: Run and verify RED**

Run: `mvn -q -pl career-web -am -Dtest=AcquisitionEndToEndTest test`

Expected: FAIL until all wiring, transaction boundaries, and fixture selectors operate together.

- [ ] **Step 3: Fix only integration gaps and make the acceptance test GREEN**

Do not introduce new features. Correct bean wiring, transaction demarcation, DTO mapping, or fixture selectors revealed by the test.

- [ ] **Step 4: Add opt-in live smoke category**

Tag the test `acquisition-live`. It fetches the two seeded entry URLs once each, limits discovery to one page, and asserts at least one valid official article link or emits a source-specific compatibility assertion showing the failing selector. Add a Maven `acquisition-live` profile that excludes the test by default and runs it only with:

```powershell
mvn -q -Pacquisition-live -Dcareer-os.acquisition.live-smoke-enabled=true -Dtest=OfficialSourceLiveSmokeTest test
```

- [ ] **Step 5: Document operations and recovery**

`docs/PHASE3_API.md` must include request/response examples for source listing, manual run, run status, and cursor consumption; environment variables; schedule times; retry/deactivation semantics; and how to disable a changed source safely. Update README with Phase 3 scope and commands.

- [ ] **Step 6: Run complete verification**

Run: `mvn -q clean test`

Expected: all default tests PASS with zero failures and zero errors.

Run: `mvn -q -DskipTests package`

Expected: executable `career-web` JAR packages successfully.

Run the opt-in live smoke command from Step 4.

Expected: both official entry pages pass compatibility checks. If an official site is temporarily unreachable, retain the deterministic suite result and record the exact URL/status/timeout in the final acceptance report; do not weaken selectors or fabricate success.

- [ ] **Step 7: Commit**

```bash
git add pom.xml README.md docs/PHASE3_API.md career-web/src/test
git commit -m "test: accept phase 3 acquisition flow"
```

---

## Plan Self-Review Results

- Spec coverage: all architecture, persistence, delta, deactivation, safety, scheduling, API, metrics, test, and acceptance requirements map to Tasks 1-9.
- Placeholder scan: no unfinished markers or unspecified error-handling steps remain.
- Type consistency: `RecruitmentSource`, `SourceCrawlRun`, `AcquiredDocument`, `AcquisitionChange`, `AcquisitionStore`, `SourceRunLock`, `FetchedDocument`, `AcquiredDocumentProcessor`, and `AcquisitionService` retain the same names and roles across tasks.
- Scope check: this plan delivers only the approved two-source Phase 3A backend; notification adapters and source expansion remain separate future designs.
