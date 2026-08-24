# Official Source Expansion Wave 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add repeatable, evidence-audited 2024—2026 acquisition for HDU, ZJGSU, and Hangzhou First People's Hospital without weakening HTTPS or falsely marking incomplete sources as connected.

**Architecture:** Move listing traversal behind a source-listing port, route parsing by explicit adapter type, and keep discovered artifacts on the existing download/fingerprint/diff pipeline. Persist onboarding checkpoints, annual traversal evidence, and row failures; project target-source status only from those facts. Parse the hospital's 2024 HTTPS job tables deterministically and collect 2025—2026 HTTPS official notices while preserving the HTTP application URL as non-fetchable evidence.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Data JPA, PostgreSQL/Flyway, Jackson, Jsoup, Apache POI, JUnit 5, AssertJ, Mockito, WireMock, React 19, TypeScript, TanStack Query, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-08-24-official-source-expansion-wave1-design.md`

## Global Constraints

- Automatic acquisition may request only absolute HTTPS URLs on allowlisted official hosts.
- Preserve `http://zhaopin.hz-hospital.com:8080/` as an application link; never send an automated request to it.
- A source becomes `CONNECTED` only when 2024, 2025, and 2026 coverage all support an absence conclusion and a fresh incremental run succeeds.
- Configuration, a reachable URL, or a non-empty page does not prove complete coverage.
- ZJGSU WeChat links are discovery leads unless the account and stable evidence are verified; they cannot override official on-domain facts.
- One workbook row failure must not block successful rows and must not be counted as a remote-source access failure.
- A failed request keeps the last successful data and cannot create a deactivation.
- Use stable official URI, official job code, or batch + organization/department + title for identity; mutable qualifications and headcount belong only in the content fingerprint.
- Do not automate login, CAPTCHA, registration, application, or submission.
- All production changes follow red-green-refactor TDD and each task ends with a focused commit.

## File and Responsibility Map

- Domain records own onboarding, coverage, and failure invariants.
- Application ports own listing and persistence contracts; `AcquisitionService` remains orchestration-only.
- Infrastructure classes own page traversal, site adapters, document parsing, normalization, and PostgreSQL mapping.
- Web/API classes expose source evidence; the React update workbench explains partial coverage.
- Migrations V29/V30 add audit storage and seed only verified HTTPS source inputs.
- Acceptance scripts run real backfills twice and report measured differences.

---

### Task 1: Replace JCMS-only historical traversal with a source-listing port

**Files:**
- Modify: `career-application/src/main/java/com/careeros/application/AcquisitionHttpPorts.java`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/ConfigurableSourceListingReader.java`
- Modify: `career-application/src/main/java/com/careeros/application/AcquisitionService.java`
- Modify: `career-web/src/main/java/com/careeros/ApplicationConfiguration.java`
- Create test: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/ConfigurableSourceListingReaderTest.java`
- Modify test: `career-application/src/test/java/com/careeros/application/AcquisitionServiceTest.java`

**Interfaces:**
- Produces `SourceListingReader.read(RecruitmentSource source, ListingQuery query)`.
- Produces `ListingQuery(Set<Integer> recruitmentYears, boolean historical)`.
- Produces `ListingResult(List<YearDiscoveredLink> links, Map<Integer, ListingEvidence> evidenceByYear)`.
- `YearDiscoveredLink` includes the recruitment year; `AcquisitionService` no longer guesses it after traversal.

- [ ] **Step 1: Write failing traversal tests**

```java
@Test
void staticSuffixTraversalStopsAfterCrossingRequestedYears() {
    var result = reader.read(hduSource(), new ListingQuery(Set.of(2024, 2025, 2026), true));
    assertThat(result.links()).extracting(YearDiscoveredLink::recruitmentYear)
        .containsOnly(2024, 2025, 2026);
    assertThat(result.evidenceByYear().get(2024).stopReason())
        .isEqualTo("OLDER_THAN_REQUESTED_YEAR");
}

@Test
void repeatedNonTerminalPageIsAContractFailure() {
    assertThatThrownBy(() -> reader.read(repeatingSource(), historical2024To2026()))
        .isInstanceOf(FetchFailedException.class)
        .hasMessageContaining("repeated a non-terminal page");
}

@Test
void listingAccessFailureKeepsPreviouslyActiveDocuments() {
    store.saveDocument(activeDocument());
    fetcher.failListingWithTimeout();
    var run = service.run(SOURCE_ID, MANUAL);
    assertThat(run.status()).isEqualTo(FAILED);
    assertThat(store.findDocuments(SOURCE_ID)).allMatch(document -> document.state() == ACTIVE);
}
```

- [ ] **Step 2: Run tests and verify RED**

```powershell
mvn -pl career-application,career-infrastructure -am -Dtest=AcquisitionServiceTest,ConfigurableSourceListingReaderTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: listing contracts and implementation are missing.

- [ ] **Step 3: Add exact listing contracts**

```java
public interface SourceListingReader {
    ListingResult read(RecruitmentSource source, ListingQuery query);
}

public record ListingEvidence(
    int pageCount, int rawCount, int acceptedCount, int filteredCount, int failedCount,
    LocalDate earliestPublishedOn, LocalDate latestPublishedOn,
    boolean traversalComplete, String stopReason, String completionBasis
) {}
```

Reject completed evidence when `stopReason` or `completionBasis` is blank.

- [ ] **Step 4: Implement deterministic traversal modes**

```text
JCMS_PARAM_JSON       existing paramJson pageNo/pageSize/total-count behavior
STATIC_PAGE_SUFFIX    page 1 = list.htm; page n = list{n}.htm
FIXED_HTTPS_EVIDENCE  explicit HTTPS detail URIs grouped by year
```

Canonicalize and deduplicate links, hash pages to detect repetition, and return `traversalComplete=false` for fixed evidence sets.

- [ ] **Step 5: Refactor `AcquisitionService` to consume annual evidence**

Remove `historicalDetails`, `listingPageUri`, and `listingTotal`. Only complete traversal with zero failures and successful document processing may become `COMPLETE` or `NO_TARGET_RECORDS`.

```java
ListingResult listing = listings.read(source, new ListingQuery(recruitmentYears, true));
for (YearDiscoveredLink discovered : listing.links()) {
    acquire(source, runId, discovered.link(), null, DocumentKind.ANNOUNCEMENT,
        discovered.link().title(), counts);
}
persistCoverage(source.id(), listing.evidenceByYear(), processingByYear);
```

- [ ] **Step 6: Run tests and commit**

```powershell
mvn -pl career-application,career-infrastructure,career-web -am -Dtest=AcquisitionServiceTest,ConfigurableSourceListingReaderTest,CareerOsApplicationTest -Dsurefire.failIfNoSpecifiedTests=false test
git add career-application career-infrastructure career-web
git commit -m "refactor: support auditable source listing traversal"
```

Expected: legacy JCMS tests and new traversal tests pass.

---

### Task 2: Route discovery by explicit adapter type and enforce HTTPS

**Files:**
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/RoutingSourceDiscoverer.java`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/HospitalOfficialEvidenceDiscoverer.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/StaticHtmlSourceDiscoverer.java`
- Modify: `career-web/src/main/java/com/careeros/ApplicationConfiguration.java`
- Create test: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/RoutingSourceDiscovererTest.java`
- Create test: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/HospitalOfficialEvidenceDiscovererTest.java`
- Create fixture: `career-infrastructure/src/test/resources/fixtures/acquisition/wave1/hospital-official-list.html`

**Interfaces:**
- Consumes `RecruitmentSource.configuration().get("adapterType")`.
- Produces a single `RoutingSourceDiscoverer` bean implementing `SourceDiscoverer`.
- Supports `JCMS_LISTING`, `STATIC_HTML`, and `HOSPITAL_OFFICIAL_EVIDENCE`.

- [ ] **Step 1: Write failing routing and security tests**

```java
@Test
void routesHospitalSourcesToHospitalDiscoverer() {
    routing.discover(hospitalSource(), LISTING_URI, fixture());
    verify(hospital).discover(hospitalSource(), LISTING_URI, fixture());
    verifyNoInteractions(staticHtml);
}

@Test
void hospitalDiscoveryRejectsHttpApplicationLinks() {
    var links = discoverer.discover(hospitalSource(), OFFICIAL_HTTPS_PAGE, fixture());
    assertThat(links).allMatch(link -> "https".equals(link.uri().getScheme()));
    assertThat(links).noneMatch(link -> link.uri().toString()
        .contains("zhaopin.hz-hospital.com:8080"));
}
```

- [ ] **Step 2: Run tests and verify RED**

```powershell
mvn -pl career-infrastructure,career-web -am -Dtest=RoutingSourceDiscovererTest,HospitalOfficialEvidenceDiscovererTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: router and hospital delegate are missing.

- [ ] **Step 3: Implement routing and hospital allowlists**

Hospital discovery accepts only `zp.hz-hospital.com`, `www.hz-hospital.com`, `wsjkw.hangzhou.gov.cn`, and `hrss.hangzhou.gov.cn` over HTTPS. Unknown adapter values fail as `DISCOVERY_CONTRACT_CHANGED`; no fallback is allowed. The HTTP application URL is parsed only as evidence text, never as `DiscoveredLink`.

```java
private static final Set<String> HOSPITAL_HTTPS_HOSTS = Set.of(
    "zp.hz-hospital.com", "www.hz-hospital.com",
    "wsjkw.hangzhou.gov.cn", "hrss.hangzhou.gov.cn");
```

- [ ] **Step 4: Wire the router as the only source-discoverer bean**

```java
@Bean SourceDiscoverer sourceDiscoverer(
    StaticHtmlSourceDiscoverer staticHtml,
    HospitalOfficialEvidenceDiscoverer hospital
) { return new RoutingSourceDiscoverer(staticHtml, hospital); }
```

- [ ] **Step 5: Run tests and commit**

```powershell
mvn -pl career-infrastructure,career-web -am -Dtest=RoutingSourceDiscovererTest,HospitalOfficialEvidenceDiscovererTest,StaticHtmlSourceDiscovererTest -Dsurefire.failIfNoSpecifiedTests=false test
git add career-infrastructure career-web
git commit -m "feat: route official source discovery safely"
```

---

### Task 3: Persist onboarding evidence, annual traversal metrics, and import failures

**Files:**
- Create: `career-domain/src/main/java/com/careeros/domain/acquisition/SourceOnboardingCheckpoint.java`
- Create: `career-domain/src/main/java/com/careeros/domain/acquisition/ArtifactImportFailure.java`
- Modify: `career-domain/src/main/java/com/careeros/domain/acquisition/SourceYearCoverage.java`
- Modify: `career-application/src/main/java/com/careeros/application/AcquisitionPorts.java`
- Create: `career-infrastructure/src/main/resources/db/migration/V29__source_onboarding_and_import_failures.sql`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/AcquisitionJpaModels.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/JpaAcquisitionStore.java`
- Modify test: `career-infrastructure/src/test/java/com/careeros/infrastructure/MigrationIntegrationTest.java`
- Create test: `career-domain/src/test/java/com/careeros/domain/acquisition/SourceOnboardingCheckpointTest.java`
- Create test: `career-domain/src/test/java/com/careeros/domain/acquisition/ArtifactImportFailureTest.java`
- Modify test: `career-domain/src/test/java/com/careeros/domain/acquisition/SourceYearCoverageTest.java`
- Modify test: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/JpaAcquisitionStoreTest.java`

**Interfaces:**
- Produces: `SourceOnboardingCheckpoint(UUID sourceId, Checkpoint checkpoint, CheckpointStatus status, String evidence, Instant verifiedAt)`.
- Produces: `ArtifactImportFailure(UUID id, UUID runId, UUID sourceId, UUID documentId, FailureStage stage, String sheetName, Integer rowNumber, String errorCode, String safeMessage, Instant occurredAt)`.
- Extends `SourceYearCoverage` with `listingPageCount`, `filteredCount`, `failedCount`, `earliestPublishedOn`, `latestPublishedOn`, and `stopReason`; retain a compatibility constructor.
- Extends `AcquisitionStore` with `saveCheckpoint`, `findCheckpoints`, `saveImportFailures`, and `findImportFailures`.

- [ ] **Step 1: Write failing domain and migration tests**

```java
@Test
void connectedCheckpointRequiresVerifiableEvidence() {
    assertThatThrownBy(() -> new SourceOnboardingCheckpoint(SOURCE_ID,
        BACKFILL_COMPLETE, VERIFIED, " ", NOW))
        .isInstanceOf(IllegalArgumentException.class);
}

@Test
void rowFailureRequiresSheetAndPositiveRow() {
    assertThatThrownBy(() -> new ArtifactImportFailure(ID, RUN_ID, SOURCE_ID, DOCUMENT_ID,
        ROW_PARSE_FAILED, null, 0, "MISSING_ORGANIZATION", "招聘单位为空", NOW))
        .isInstanceOf(IllegalArgumentException.class);
}

@Test
void partialCoverageCannotSupportAbsenceConclusion() {
    var coverage = coverage(PARTIAL, 3, 8, 1, "document failures");
    assertThat(coverage.supportsAbsenceConclusion()).isFalse();
    assertThat(coverage.failedCount()).isEqualTo(1);
}
```

- [ ] **Step 2: Run tests and verify RED**

```powershell
mvn -pl career-domain,career-infrastructure -am -Dtest=SourceOnboardingCheckpointTest,ArtifactImportFailureTest,SourceYearCoverageTest,MigrationIntegrationTest,JpaAcquisitionStoreTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because the records, fields, tables, and ports do not exist.

- [ ] **Step 3: Add domain records and V29 schema**

Use these exact enums:

```java
enum Checkpoint { REGISTERED, CONTRACT_VERIFIED, LIVE_SMOKE_VERIFIED, BACKFILL_COMPLETE, INCREMENTAL_VERIFIED }
enum CheckpointStatus { PENDING, VERIFIED, FAILED }
enum FailureStage {
    DISCOVERY_CONTRACT_CHANGED, REMOTE_ACCESS_FAILED, ARTIFACT_DOWNLOAD_FAILED,
    UNSUPPORTED_DOCUMENT, DOCUMENT_PARSE_FAILED, ROW_PARSE_FAILED,
    NORMALIZATION_FAILED, EVIDENCE_LINK_FAILED
}
```

Create checkpoint and failure tables, indexes on `(source_id, occurred_at desc)` and `(document_id, stage)`, and six coverage columns with nonnegative checks and zero/null defaults.

- [ ] **Step 4: Implement JPA mapping, safe messages, and store operations**

```java
private static String safeMessage(String value) {
    if (value == null || value.isBlank()) return "未提供错误详情";
    String noPhone = value.replaceAll("(?<!\\d)1\\d{10}(?!\\d)", "[手机号已隐藏]");
    String noId = noPhone.replaceAll("(?<!\\d)\\d{17}[0-9Xx](?!\\d)", "[身份证号已隐藏]");
    return noId.length() <= 500 ? noId : noId.substring(0, 500);
}
```

- [ ] **Step 5: Run tests and verify GREEN**

Run the command from Step 2. Expected: all listed tests pass and legacy coverage rows deserialize.

- [ ] **Step 6: Commit**

```powershell
git add career-domain career-application career-infrastructure
git commit -m "feat: persist source onboarding and import failures"
```

---

### Task 4: Register HDU and ZJGSU as scheduled sources

**Files:**
- Create: `career-infrastructure/src/main/resources/db/migration/V30__wave1_official_recruitment_sources.sql`
- Modify: `career-infrastructure/src/main/resources/official-source-catalog.yml`
- Modify test: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/OfficialSourceCatalogTest.java`
- Modify test: `career-infrastructure/src/test/java/com/careeros/infrastructure/MigrationIntegrationTest.java`
- Modify test: `career-web/src/test/java/com/careeros/OfficialSourceLiveSmokeTest.java`
- Create fixtures: `career-infrastructure/src/test/resources/fixtures/acquisition/wave1/hdu-list-1.html`, `hdu-list-2.html`, `zjgsu-list-1.html`

**Interfaces:**
- Produces source IDs `01992f09-0000-7000-8000-000000000303` (`HDU_RECRUITMENT`) and `...0304` (`ZJGSU_RECRUITMENT`).
- Both use `STATIC_PAGE_SUFFIX`, `adapterType=STATIC_HTML`, and `historicalYears=[2024,2025,2026]`.

- [ ] **Step 1: Write failing catalog and migration assertions**

```java
assertThat(catalog.sources()).filteredOn(SourceDefinition::enabled)
    .extracting(SourceDefinition::code)
    .contains("HDU_RECRUITMENT", "ZJGSU_RECRUITMENT");
assertThat(source("HDU_RECRUITMENT").configuration())
    .containsEntry("historicalPaginationMode", "STATIC_PAGE_SUFFIX")
    .containsEntry("adapterType", "STATIC_HTML");
```

Assert both `recruitment_source` rows exist and both target catalog rows remain `PARTIAL` before evidence projection.

- [ ] **Step 2: Run tests and verify RED**

```powershell
mvn -pl career-infrastructure,career-web -am -Dtest=MigrationIntegrationTest,OfficialSourceCatalogTest,OfficialSourceLiveSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: source rows and runtime configurations are absent.

- [ ] **Step 3: Seed exact source configurations**

```text
HDU entry https://renshi.hdu.edu.cn/rczp/list.htm
HDU regex ^https://renshi\.hdu\.edu\.cn/[0-9]{4}/[0-9]{4}/c[0-9]+a[0-9]+/page\.htm$
ZJGSU entry https://talents.zjgsu.edu.cn/rczp/list.htm
ZJGSU regex ^https://talents\.zjgsu\.edu\.cn/[0-9]{4}/[0-9]{4}/c[0-9]+a[0-9]+/page\.htm$
Cron HDU 08:30 and ZJGSU 08:40 Asia/Shanghai
Minimum interval 1500 ms
```

V30 inserts `NOT_DISCOVERED` coverage rows for 2024—2026, links the target catalog rows, and does not set either source to `CONNECTED`.

Insert `REGISTERED=VERIFIED` checkpoints in V30 because source identity and official roots are known; do not insert contract, live-smoke, backfill, or incremental checkpoints.

- [ ] **Step 4: Add sanitized fixtures and filtering tests**

Include an HDU 2026 labor-dispatch announcement, a second-page 2024 item, a ZJGSU on-domain item, and an external WeChat item. Assert the WeChat item is excluded from accepted artifacts and counted as filtered.

```html
<a href="/2026/0313/c13762a290230/page.htm">杭州电子科技大学公开招聘工作人员（劳务派遣）公告</a>
<a href="https://mp.weixin.qq.com/s/example">广纳贤才！浙商大全球公开招聘</a>
```

- [ ] **Step 5: Run tests and commit**

```powershell
mvn -pl career-infrastructure,career-web -am -Dtest=MigrationIntegrationTest,OfficialSourceCatalogTest,StaticHtmlSourceDiscovererTest,ConfigurableSourceListingReaderTest -Dsurefire.failIfNoSpecifiedTests=false test
git add career-infrastructure career-web
git commit -m "feat: register HDU and ZJGSU acquisition sources"
```

---

### Task 5: Parse and import Hangzhou First Hospital HTTPS job tables

**Files:**
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/HospitalOfficialPageParser.java`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/OfficialJobFieldMapper.java`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/HospitalOfficialJobImportService.java`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/OfficialRecruitmentEventMatcher.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/OfficialExcelImportService.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/Phase2DocumentProcessor.java`
- Create test: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/HospitalOfficialPageParserTest.java`
- Create test: `career-infrastructure/src/test/java/com/careeros/infrastructure/persistence/HospitalOfficialJobImportServiceTest.java`
- Modify tests: `career-infrastructure/src/test/java/com/careeros/infrastructure/persistence/OfficialExcelImportServiceTest.java`, `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/Phase2DocumentProcessorTest.java`
- Create fixtures: `career-infrastructure/src/test/resources/fixtures/acquisition/wave1/hospital-2024-information-job.html`, `hospital-2024-exam-followup.html`

**Interfaces:**
- Produces `ParsedHospitalAnnouncement(String title, LocalDate publishedOn, String applicationUrl, List<HospitalJobRow> jobs)`.
- Produces `HospitalJobRow(String department, String title, String category, String educationDegree, String majors, String candidateScope, String headcount, String ageLimit)`.
- Produces `RawOfficialJob(String externalJobCode, String title, String duties, String majorText, String educationText, String employmentText, String headcountText, String candidateScope, String ageText, String experienceText, String professionalTitleText)`.
- Produces `ImportContext(UUID eventId, UUID organizationId, String organizationName, String location, LocalDate ageReferenceDate, String sourceUrl, String stableSourceUrl, List<UUID> evidenceIds)`.
- Produces `OfficialJobFieldMapper.toNormalizedJob(RawOfficialJob row, ImportContext context)` for both Excel and hospital HTML.
- Produces `OfficialRecruitmentEventMatcher.findEvent(String organizationName, int recruitmentYear, String announcementTitle)` so later exam notices update the original batch.

- [ ] **Step 1: Write failing parser and stable-key tests**

```java
@Test
void parsesPublicHospitalJobTableAndPreservesHttpApplicationUrl() {
    var parsed = parser.parse(URI.create(
        "https://zp.hz-hospital.com/index/index/announcement_desc/id/212.html"), fixture());
    assertThat(parsed.applicationUrl()).isEqualTo("http://zhaopin.hz-hospital.com:8080/");
    assertThat(parsed.jobs()).anySatisfy(job -> {
        assertThat(job.department()).isEqualTo("X-信息中心");
        assertThat(job.majors()).contains("计算机科学与技术");
    });
}

@Test
void qualificationChangeUpdatesSameHospitalJob() {
    var first = imports.importAnnouncement(command(), parsed("本科/学士", "≤35周岁"));
    var second = imports.importAnnouncement(command(), parsed("硕士研究生/硕士", "≤38周岁"));
    assertThat(first.inserted()).isEqualTo(1);
    assertThat(second.updated()).isEqualTo(1);
    assertThat(second.inserted()).isZero();
}

@Test
void followupNoticeUpdatesProcessWithoutDuplicatingJobs() {
    imports.importAnnouncement(command(), recruitmentWithOneJob());
    imports.importAnnouncement(followupCommand(), examFollowup());
    assertThat(jobPostings.findAll()).hasSize(1);
    assertThat(events.findAll()).singleElement()
        .satisfies(event -> assertThat(event.interviewOn).isNotNull());
}
```

- [ ] **Step 2: Run tests and verify RED**

```powershell
mvn -pl career-infrastructure -am -Dtest=HospitalOfficialPageParserTest,HospitalOfficialJobImportServiceTest,OfficialExcelImportServiceTest,Phase2DocumentProcessorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: parser, importer, and shared field mapper are missing.

- [ ] **Step 3: Implement deterministic public-table parsing**

Select the table following `岗位需求`; require department, title, category, education/degree, major, candidate scope, headcount, and age columns. A page without the table remains announcement-only evidence. Extract the HTTP application URL as text and never pass it to `DocumentFetcher`.

```java
Element table = document.select("table").stream()
    .filter(candidate -> requiredHeaders(candidate).containsAll(REQUIRED_JOB_HEADERS))
    .findFirst().orElse(null);
List<HospitalJobRow> rows = table == null ? List.of() : parseRows(table);
```

- [ ] **Step 4: Extract the shared field mapper**

Move education, major, age, experience, employment, headcount, job-family, and raw-text mapping from `OfficialExcelImportService` into `OfficialJobFieldMapper`. Existing workbook tests are the regression oracle; stable keys and normalized fields must not change.

```java
public NormalizedJob toNormalizedJob(RawOfficialJob row, ImportContext context) {
    return new NormalizedJob(context.eventId(), context.organizationId(),
        context.organizationName(), row.externalJobCode(), row.title(),
        jobFamily(row.title(), row.duties(), row.majorText()),
        employmentType(row.employmentText()), context.location(),
        Math.max(1, integer(row.headcountText(), 1)), education(row.educationText()),
        splitMajors(row.majorText()), graduationYears(row.candidateScope()),
        ageLimit(row.ageText()), context.ageReferenceDate(),
        experienceYears(row.experienceText()), professionalTitles(row.professionalTitleText()),
        row.duties(), context.sourceUrl(), context.stableSourceUrl(), null,
        context.evidenceIds());
}
```

- [ ] **Step 5: Integrate hospital HTML processing**

For `zp.hz-hospital.com/.../announcement_desc/...`, run announcement fact extraction and deterministic table import in one transaction. Match follow-up notices by organization, recruitment year, and a normalized batch title that removes bracketed status prefixes plus `成绩`, `入围`, `后续事项`, `面试`, or `体检` suffixes; ambiguous matches create `EVIDENCE_LINK_FAILED` instead of merging. Main-site pages without a public table stay on the HTML extraction path.

```java
if (hospitalPages.supports(command.documentUri())) {
    ParsedHospitalAnnouncement parsed = hospitalPages.parse(command.documentUri(), command.content());
    return hospitalImports.importAnnouncement(command, parsed).toProcessingResult();
}
return processGenericHtml(command);
```

- [ ] **Step 6: Run tests and commit**

```powershell
mvn -pl career-infrastructure,career-web -am -Dtest=HospitalOfficialPageParserTest,HospitalOfficialJobImportServiceTest,OfficialExcelImportServiceTest,Phase2DocumentProcessorTest,OfficialWorkbookIdentityTest -Dsurefire.failIfNoSpecifiedTests=false test
git add career-infrastructure career-web
git commit -m "feat: import official hospital job tables"
```

---

### Task 6: Register hospital evidence and persist row-level failures

**Files:**
- Modify: `career-infrastructure/src/main/resources/db/migration/V30__wave1_official_recruitment_sources.sql`
- Modify: `career-infrastructure/src/main/resources/official-source-catalog.yml`
- Modify: `career-application/src/main/java/com/careeros/application/AcquiredDocumentProcessor.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/Phase2DocumentProcessor.java`
- Modify: `career-application/src/main/java/com/careeros/application/AcquisitionService.java`
- Modify tests: `career-infrastructure/src/test/java/com/careeros/infrastructure/MigrationIntegrationTest.java`, `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/Phase2DocumentProcessorTest.java`, `career-application/src/test/java/com/careeros/application/AcquisitionServiceTest.java`

**Interfaces:**
- Produces source ID `01992f09-0000-7000-8000-000000000305` for `HZ_FIRST_HOSPITAL`.
- Extends `ProcessingResult` with `List<ProcessingIssue> issues` and a compatibility constructor.
- `ProcessingIssue` contains `FailureStage stage`, `String sheetName`, `Integer rowNumber`, `String errorCode`, and `String safeMessage`.

- [ ] **Step 1: Write failing persistence and security tests**

```java
@Test
void rowErrorsArePersistedWithoutFailingSuccessfulRows() {
    var run = service.run(HOSPITAL_SOURCE_ID, MANUAL);
    assertThat(run.status()).isEqualTo(PARTIALLY_SUCCEEDED);
    assertThat(store.findImportFailures(HOSPITAL_SOURCE_ID, DOCUMENT_ID))
        .singleElement().satisfies(failure -> {
            assertThat(failure.stage()).isEqualTo(ROW_PARSE_FAILED);
            assertThat(failure.rowNumber()).isEqualTo(3);
        });
    assertThat(run.addedCount()).isEqualTo(1);
}

@Test
void sourceConfigurationContainsNoFetchableHttpUri() {
    assertThat(source("HZ_FIRST_HOSPITAL").configuration().toString())
        .doesNotContain("http://zhaopin.hz-hospital.com:8080");
}
```

- [ ] **Step 2: Run tests and verify RED**

```powershell
mvn -pl career-application,career-infrastructure -am -Dtest=AcquisitionServiceTest,Phase2DocumentProcessorTest,MigrationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: issues are not carried/persisted and the hospital source is absent.

- [ ] **Step 3: Seed fixed HTTPS hospital evidence**

Use `adapterType=HOSPITAL_OFFICIAL_EVIDENCE` and `historicalPaginationMode=FIXED_HTTPS_EVIDENCE`:

```text
2024 https://zp.hz-hospital.com/index/index/announcement_desc/id/176.html
2024 https://zp.hz-hospital.com/index/index/announcement_desc/id/181.html
2024 https://zp.hz-hospital.com/index/index/announcement_desc/id/188.html
2024 https://zp.hz-hospital.com/index/index/announcement_desc/id/207.html
2024 https://zp.hz-hospital.com/index/index/announcement_desc/id/212.html
2024 https://zp.hz-hospital.com/index/index/announcement_desc/id/231.html
2025 https://www.hz-hospital.com/content/details/id/224426?cid=68
2026 https://www.hz-hospital.com/content/details/id/227185?cid=68
2026 https://www.hz-hospital.com/content/details/id/228230?cid=68
```

Set the schedulable HTTPS `entry_uri` to `https://www.hz-hospital.com/`, restrict accepted detail URLs to `^https://www\.hz-hospital\.com/content/details/id/[0-9]+(?:\?cid=[0-9]+)?$`, and include only titles matching `招聘|招贤|人才`. This supports safe current discovery but does not prove archive completeness.

Fixed evidence always reports incomplete traversal, so annual status remains `PARTIAL` until a complete HTTPS listing contract exists.

- [ ] **Step 4: Carry processing issues into failure records**

Map workbook row errors to `ROW_PARSE_FAILED`, unsupported files to `UNSUPPORTED_DOCUMENT`, and parser exceptions to `DOCUMENT_PARSE_FAILED`. Persist after the acquired document has an ID. Row issues make a run partial but do not increment `RecruitmentSource.consecutiveFailureCount`.

```java
List<ArtifactImportFailure> failures = processing.issues().stream()
    .map(issue -> issue.toFailure(runId, source.id(), document.id(), clock.instant()))
    .toList();
store.saveImportFailures(failures);
```

- [ ] **Step 5: Run tests and commit**

```powershell
mvn -pl career-application,career-infrastructure,career-web -am -Dtest=AcquisitionServiceTest,Phase2DocumentProcessorTest,JpaAcquisitionStoreTest,MigrationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
git add career-application career-infrastructure career-web
git commit -m "feat: audit partial hospital and workbook imports"
```

---

### Task 7: Project truthful source status and expose it in the workbench

**Files:**
- Create: `career-application/src/main/java/com/careeros/application/SourceConnectionProjector.java`
- Modify: `career-application/src/main/java/com/careeros/application/AcquisitionPorts.java`
- Modify: `career-application/src/main/java/com/careeros/application/AcquisitionService.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/JpaAcquisitionStore.java`
- Modify: `career-web/src/main/java/com/careeros/AcquisitionApiModels.java`
- Modify: `career-web/src/main/java/com/careeros/AcquisitionController.java`
- Modify: `career-web/src/main/java/com/careeros/ApplicationConfiguration.java`
- Create test: `career-application/src/test/java/com/careeros/application/SourceConnectionProjectorTest.java`
- Modify tests: `career-application/src/test/java/com/careeros/application/AcquisitionServiceTest.java`, `career-web/src/test/java/com/careeros/AcquisitionApiTest.java`
- Modify: `career-ui/src/features/updates/updateApi.ts`
- Modify: `career-ui/src/features/updates/SourceList.tsx`
- Modify test: `career-ui/src/features/updates/UpdatesPage.test.tsx`

**Interfaces:**
- Produces `SourceConnectionProjector.refresh(UUID sourceId, Instant now)`.
- Extends `AcquisitionStore` with `findLatestRun` and `updateTargetSourceStatus(String sourceCode, ConnectionStatus status, UUID recruitmentSourceId, Instant updatedAt)`.
- Extends `SourceResponse` with `connectionStatus`, `List<CoverageResponse> coverage`, `List<CheckpointResponse> checkpoints`, and `int unresolvedFailureCount`.
- Produces `GET /api/acquisition/sources/{sourceId}/failures?size=50` returning newest safe failure records.

- [ ] **Step 1: Write failing projection and UI tests**

```java
@Test
void threeCompleteYearsAndFreshIncrementalRunProduceConnected() {
    when(store.findSourceYearCoverage(SOURCE_ID, null)).thenReturn(complete2024To2026());
    when(store.findLatestRun(SOURCE_ID)).thenReturn(Optional.of(succeededIncremental(NOW)));
    projector.refresh(SOURCE_ID, NOW);
    verify(store).updateTargetSourceStatus("HDU_RECRUITMENT", CONNECTED, SOURCE_ID, NOW);
}

@Test
void fixedEvidenceHospitalRemainsPartial() {
    when(store.findSourceYearCoverage(SOURCE_ID, null)).thenReturn(partial2024To2026());
    projector.refresh(SOURCE_ID, NOW);
    verify(store).updateTargetSourceStatus("HZ_FIRST_HOSPITAL", PARTIAL, SOURCE_ID, NOW);
}
```

```tsx
expect(await screen.findByText('部分接入')).toBeVisible()
expect(screen.getByText('2024：部分完成')).toBeVisible()
expect(screen.getByText('2 条待处理解析问题')).toBeVisible()
expect(screen.queryByText('来源正常')).not.toBeInTheDocument()
```

- [ ] **Step 2: Run tests and verify RED**

```powershell
mvn -pl career-application,career-web -am -Dtest=SourceConnectionProjectorTest,AcquisitionServiceTest,AcquisitionApiTest -Dsurefire.failIfNoSpecifiedTests=false test
Push-Location career-ui; npm test -- UpdatesPage.test.tsx; Pop-Location
```

Expected: projector, API fields, and UI labels are missing.

- [ ] **Step 3: Implement exact projection rules**

```text
CONNECTED     all 2024—2026 years support absence conclusions + latest run SUCCEEDED
PARTIAL       source exists and any coverage/document/failure evidence exists, but CONNECTED is false
FAILED        no successful evidence and at least 3 consecutive remote/contract failures
NOT_CONNECTED no recruitment_source row
```

Row failures alone cannot produce `FAILED`. Refresh after every terminal current or historical run.

Write checkpoints as follows: first successful deterministic listing parse verifies `CONTRACT_VERIFIED`; first successful real HTTPS fetch verifies `LIVE_SMOKE_VERIFIED`; three complete annual rows verify `BACKFILL_COMPLETE`; a successful current run after backfill verifies `INCREMENTAL_VERIFIED`. A failed attempt stores `FAILED` evidence without deleting an earlier verified checkpoint record.

- [ ] **Step 4: Return and render source evidence**

Show annual counts and stop reason. Never equate zero remote failures with connected status. Label fixed hospital evidence “部分接入：已保存官方证据，但尚不能证明年度列表完整”.

```tsx
<StatusChip tone={source.connectionStatus === 'CONNECTED' ? 'positive' : 'warning'}>
  {source.connectionStatus === 'CONNECTED' ? '完整接入' : '部分接入'}
</StatusChip>
{source.coverage.map(year => <p key={year.year}>{year.year}：{coverageLabel(year.status)}</p>)}
```

- [ ] **Step 5: Run tests and commit**

```powershell
mvn -pl career-application,career-infrastructure,career-web -am -Dtest=SourceConnectionProjectorTest,AcquisitionServiceTest,AcquisitionApiTest,JdbcCareerPlanQueryAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test
Push-Location career-ui; npm test -- UpdatesPage.test.tsx CareerPlanPage.test.tsx; Pop-Location
git add career-application career-infrastructure career-web career-ui
git commit -m "feat: expose truthful official source coverage"
```

---

### Task 8: Run real backfills, verify idempotency, and document measured results

**Files:**
- Modify: `career-web/src/test/java/com/careeros/OfficialSourceLiveSmokeTest.java`
- Create: `scripts/wave1_source_acceptance.ps1`
- Modify: `scripts/career_correctness_acceptance.ps1`
- Modify: `scripts/planning_browser_acceptance.py`
- Modify: `README.md`
- Modify: `docs/current-gap-analysis.md`

**Interfaces:**
- Consumes all previous tasks and the running PostgreSQL-backed application.
- Produces exact source states, annual coverage, deltas, failures, event/job counts, and candidate-route changes.

- [ ] **Step 1: Write acceptance assertions before live runs**

```powershell
$sources = Invoke-RestMethod "$BaseUrl/api/acquisition/sources"
$wave1 = $sources | Where-Object code -in @('HDU_RECRUITMENT','ZJGSU_RECRUITMENT','HZ_FIRST_HOSPITAL')
if ($wave1.Count -ne 3) { throw 'Wave 1 sources are not registered' }
if (($wave1 | Where-Object { $_.entryUri -like 'http://*' }).Count -gt 0) {
    throw 'Fetchable source entry must remain HTTPS'
}
if (($wave1 | Where-Object code -eq 'HZ_FIRST_HOSPITAL').connectionStatus -eq 'CONNECTED') {
    throw 'Fixed hospital evidence cannot claim complete listing coverage'
}
```

After identical consecutive runs, assert the second has `addedCount=0`, `updatedCount=0`, and no duplicate active stable keys.

- [ ] **Step 2: Run deterministic full verification**

```powershell
$taskJdk=(Resolve-Path '.tooling\temurin-21\jdk-21.0.12+8').Path
$env:JAVA_HOME=$taskJdk
$env:Path="$taskJdk\bin;$env:Path"
mvn test
Push-Location career-ui; npm run verify; npm run build; Pop-Location
```

Expected: all backend/frontend tests and the production build pass before live access.

- [ ] **Step 3: Run opt-in HTTPS smoke tests**

```powershell
mvn -pl career-web -Pacquisition-live -Dcareer-os.acquisition.live-smoke-enabled=true -Dtest=OfficialSourceLiveSmokeTest test
```

Expected: enabled HTTPS contracts pass. ZJGSU/hospital incompatibility stays `PARTIAL` or `FAILED`; never weaken TLS or assertions.

- [ ] **Step 4: Apply migrations and execute 2024—2026 backfills**

```powershell
powershell -File scripts/wave1_source_acceptance.ps1 -BaseUrl http://localhost:8080 -RunBackfill
```

The script triggers all three historical runs, one incremental run, then the same incremental run again for idempotency.

- [ ] **Step 5: Recalculate the real candidate and run browser acceptance**

```powershell
powershell -File scripts/career_correctness_acceptance.ps1 -BaseUrl http://localhost:8080
python scripts/planning_browser_acceptance.py
```

Expected: candidate `01992f09-0000-7000-8000-000000000001` remains a 2027 target-year graduate; real imported jobs expose official detail links; route counts change only with real data; browser console has zero errors.

- [ ] **Step 6: Update measured documentation and commit**

Record exact target status totals, wave-one annual states, discovered/event/job counts, row failures, test counts, and live gaps. Use “部分接入” unless complete traversal actually passes.

```powershell
git add scripts README.md docs/current-gap-analysis.md career-web/src/test/java/com/careeros/OfficialSourceLiveSmokeTest.java
git commit -m "test: verify first official source expansion wave"
```

---

## Plan Self-Review Traceability

| Spec requirement | Implementing tasks |
|---|---|
| Source onboarding lifecycle | 3, 4, 6, 7, 8 |
| HDU 2024—2026 static acquisition | 1, 2, 4, 8 |
| ZJGSU external-link boundary and truthful partial status | 2, 4, 7, 8 |
| Hospital 2024 HTTPS job tables | 2, 5, 6, 8 |
| Hospital 2025—2026 HTTPS evidence and HTTP-link preservation | 2, 6, 8 |
| Stable URI/job key/content fingerprint and idempotency | 1, 5, 8 |
| Row-level failure persistence | 3, 6, 7 |
| Complete/partial/failed coverage semantics | 1, 3, 7, 8 |
| No false deactivation on network failure | 1, 6, 8 |
| API and update-workbench visibility | 7, 8 |
| Real candidate route recalculation | 8 |
| Full test, build, API, and browser acceptance | 8 |
