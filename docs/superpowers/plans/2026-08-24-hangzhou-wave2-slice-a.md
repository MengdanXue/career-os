# Hangzhou Wave 2 Slice A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Execute this plan task-by-task with test-driven development. Subagents are disabled for this workspace, so execution is inline with a review gate after each task.

**Goal:** Establish truthful Hangzhou source coverage, remove repeated Excel headers from the job library, and provide a reusable contract for onboarding district government recruitment listings.

**Architecture:** Keep site differences in `OfficialSourceCatalog` and deterministic listing adapters. Reuse the existing acquisition, fingerprint, coverage, and evidence pipeline; add geographic scope to the target catalog rather than creating a parallel crawler. Treat a registered source, a runnable source, and a fully covered source as distinct states.

**Tech Stack:** Java 21, Spring Boot 3, Apache POI, Jsoup, PostgreSQL 16, Flyway, JUnit 5, AssertJ, React/TypeScript, Vitest.

**Spec:** `docs/superpowers/specs/2026-08-24-hangzhou-source-lifecycle-policy-wave2-design.md`

## Global Constraints

- Implement Hangzhou only; retain Zhejiang HRSS because it covers provincial organizations located in Hangzhou.
- Preserve official evidence and UNKNOWN values; do not infer employment identity or eligibility.
- All production behavior starts with a failing test and a verified RED result.
- Official fetches require HTTPS, explicit host allowlists, rate limiting, bounded response sizes, and no login/captcha bypass.
- A configured URL cannot produce `CONNECTED`; only audited annual coverage plus a fresh successful incremental run can.
- Row-level document problems cannot be projected as total source access failure.
- Do not add Kafka, Elasticsearch, microservices, or a second crawling framework.

---

### Task 1: Ignore repeated workbook header rows and remove the existing pseudo-job

**Files:**
- Modify: `career-infrastructure/src/test/java/com/careeros/infrastructure/persistence/OfficialExcelImportServiceTest.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/OfficialExcelImportService.java`
- Modify: `scripts/wave1_source_acceptance.ps1`

**Interfaces:**
- Consumes: an Apache POI `Row`, the recognized `Header`, and the same alias normalization used by `findHeader`.
- Produces: `boolean isRepeatedHeaderRow(Row row, Header header, DataFormatter formatter)`; repeated print headers are skipped before organization creation and stable-key calculation.

- [ ] **Step 1: Write the failing parser regression test**

Add a workbook fixture containing a real header, one real position, the repeated header row twice, and a second real position. Assert that `importedJobs` returns exactly the two real positions and that `JobUpsertBatch.rowErrors()` is empty.

```java
@Test
void repeatedPrintedHeadersAreIgnoredInsteadOfImportedAsJobs() throws Exception {
    assertThat(importedJobs(workbookWithRepeatedPrintedHeaders()))
        .extracting(JobUpsertService.NormalizedJob::title)
        .containsExactly("信息系统工程师", "数据治理工程师");
}
```

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
mvn -pl career-infrastructure -am -Dtest=OfficialExcelImportServiceTest#repeatedPrintedHeadersAreIgnoredInsteadOfImportedAsJobs -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because the current parser imports the first repeated header as `招聘岗位` and records a duplicate stable-key error for the second.

- [ ] **Step 3: Implement structural header-row detection**

Before extracting the title, compare populated cells at the recognized header columns with their normalized header labels. Skip a row when it repeats both a job-title alias and an organization alias, or when at least three recognized labels match and one is a job-title alias. Do not use row number, page breaks, or a source-specific special case.

```java
if (isRepeatedHeaderRow(row, header, formatter)) continue;
```

- [ ] **Step 4: Run parser and infrastructure tests**

```powershell
mvn -pl career-infrastructure -am -Dtest=OfficialExcelImportServiceTest,DefaultJobUpsertServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS; the new fixture has two jobs and zero row errors.

- [ ] **Step 5: Extend acceptance to reject pseudo-jobs**

Add an assertion that no active record has the tuple `organization='招聘单位'`, `title='招聘岗位'`, `external_job_code='序号'`. The source rerun in Task 6 must deactivate the existing record naturally through the corrected complete snapshot.

- [ ] **Step 6: Commit**

```powershell
git add career-infrastructure scripts/wave1_source_acceptance.ps1
git commit -m "fix: ignore repeated official workbook headers"
```

---

### Task 2: Complete the Hangzhou geographic target catalog

**Files:**
- Create: `career-infrastructure/src/main/resources/db/migration/V36__expand_hangzhou_target_source_scope.sql`
- Modify: `career-infrastructure/src/main/resources/official-source-catalog.yml`
- Modify: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/OfficialSourceCatalogTest.java`
- Modify: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/JpaAcquisitionStoreTest.java`

**Interfaces:**
- Consumes: the existing `target_source_catalog` connection lifecycle.
- Produces: `scope_level`, `scope_code`, `priority_tier`, and `coverage_role` metadata for each target source.

- [ ] **Step 1: Write failing catalog and migration tests**

Assert the catalog contains the ten P0 district codes:

```text
HZ_SHANGCHENG_GOV HZ_GONGSHU_GOV HZ_XIHU_GOV HZ_BINJIANG_GOV
HZ_XIAOSHAN_GOV HZ_YUHANG_GOV HZ_LINPING_GOV HZ_QIANTANG_GOV
HZ_FUYANG_GOV HZ_LINAN_GOV
```

Also assert P2 registration for `HZ_JIANDE_GOV`, `HZ_TONGLU_GOV`, and `HZ_CHUNAN_GOV`; all roots must be HTTPS and disabled/unsupported entries must remain `NOT_CONNECTED`.

- [ ] **Step 2: Verify RED**

```powershell
mvn -pl career-infrastructure -am -Dtest=OfficialSourceCatalogTest,JpaAcquisitionStoreTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because eight district targets and scope columns are absent.

- [ ] **Step 3: Add forward-only migration and seeds**

Add constrained columns:

```sql
scope_level varchar(32) not null default 'CITY',
scope_code varchar(80) not null default 'HANGZHOU',
priority_tier varchar(8) not null default 'P0',
coverage_role varchar(32) not null default 'PRIMARY'
```

Allowed values are `CITY/DISTRICT/ORGANIZATION`, `P0/P1/P2`, and `PRIMARY/SUPPLEMENTAL/DISCOVERY`. Insert missing district target rows with `NOT_CONNECTED` and no `recruitment_source_id`.

- [ ] **Step 4: Extend the YAML target registry**

Register official HTTPS roots and historical years `[2024, 2025, 2026, 2027]`. A source without a verified listing contract remains `strategy: UNSUPPORTED` and `enabled: false`.

- [ ] **Step 5: Run migration and catalog tests, then commit**

```powershell
mvn -pl career-infrastructure -am -Dtest=OfficialSourceCatalogTest,JpaAcquisitionStoreTest -Dsurefire.failIfNoSpecifiedTests=false test
git add career-infrastructure
git commit -m "feat: define complete Hangzhou source scope"
```

---

### Task 3: Add deterministic linked-page traversal for district listings

**Files:**
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/ConfigurableSourceListingReader.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/OfficialSourceCatalog.java`
- Modify: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/ConfigurableSourceListingReaderTest.java`
- Create: `career-infrastructure/src/test/resources/acquisition/district/list-page-1.html`
- Create: `career-infrastructure/src/test/resources/acquisition/district/list-page-2.html`

**Interfaces:**
- Consumes configuration `historicalPaginationMode=LINKED_PAGE`, `nextPageSelector`, and `historicalMaxPages`.
- Produces an audited `ListingResult` with visited page count, raw/filtered counts, year boundaries, cycle detection, and an explicit stop reason.

- [ ] **Step 1: Write failing traversal tests**

Cover two same-host HTTPS pages, a terminal page without a next link, duplicate articles, a cross-host next link, and a pagination cycle. The successful case must return one canonical article per URI and `supportsAbsenceConclusion=true`; unsafe/cyclic cases must throw `FetchFailedException`.

- [ ] **Step 2: Verify RED**

```powershell
mvn -pl career-infrastructure -am -Dtest=ConfigurableSourceListingReaderTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because `LINKED_PAGE` is not supported.

- [ ] **Step 3: Implement minimal `readLinkedPages`**

Fetch the entry URI, parse `nextPageSelector` with Jsoup, resolve and canonicalize the next URI, require HTTPS and an allowed official host, and stop on a missing next link. Reject repeated page URIs, repeated non-terminal content fingerprints, and page counts above `historicalMaxPages`.

- [ ] **Step 4: Run acquisition tests and commit**

```powershell
mvn -pl career-infrastructure -am -Dtest=ConfigurableSourceListingReaderTest,StaticHtmlSourceDiscovererTest,RoutingSourceDiscovererTest -Dsurefire.failIfNoSpecifiedTests=false test
git add career-infrastructure
git commit -m "feat: traverse official district listing pages"
```

---

### Task 4: Onboard the first verified Hangzhou district source contracts

**Files:**
- Create: `career-infrastructure/src/main/resources/db/migration/V37__onboard_hangzhou_district_sources.sql`
- Modify: `career-infrastructure/src/main/resources/official-source-catalog.yml`
- Modify: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/OfficialSourceCatalogTest.java`
- Modify: `career-web/src/test/java/com/careeros/OfficialSourceLiveSmokeTest.java`
- Create fixtures under: `career-infrastructure/src/test/resources/acquisition/hangzhou-districts/`

**Interfaces:**
- Consumes: verified listing URL, article regex, pagination contract, title filters, and optional allowed hosts.
- Produces: runnable `recruitment_source` rows with a truthful initial `PARTIAL` projection and annual `NOT_DISCOVERED` coverage rows.

- [ ] **Step 1: Write failing source-contract tests**

Start with the official listings already identified in the catalog and current official search evidence: Yuhang, Qiantang, Xiaoshan, Linping, Gongshu, and Xihu. Assert recruitment announcements and lifecycle-shaped titles are both discoverable by `discoverAll`, while the recruitment pipeline still admits only initial/supplement/correction titles until Slice B classifies lifecycle notices.

- [ ] **Step 2: Verify RED**

```powershell
mvn -pl career-infrastructure,career-web -am -Dtest=OfficialSourceCatalogTest,ConfigurableSourceListingReaderTest,OfficialSourceLiveSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 3: Add source rows and conservative contracts**

Insert only contracts proven by fixtures and opt-in HTTPS smoke tests. Set `historicalYears` to `[2024, 2025, 2026, 2027]`; set `next_due_at=now()` and `connection_status=PARTIAL`. Do not mark annual coverage complete before a full live traversal.

- [ ] **Step 4: Run offline and opt-in live contract tests**

```powershell
mvn -pl career-infrastructure,career-web -am -Dtest=OfficialSourceCatalogTest,ConfigurableSourceListingReaderTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl career-web -Pacquisition-live -Dcareer-os.acquisition.live-smoke-enabled=true -Dtest=OfficialSourceLiveSmokeTest test
```

If a live contract fails, leave that source disabled and `NOT_CONNECTED`; never weaken host or TLS validation.

- [ ] **Step 5: Commit measured source states**

```powershell
git add career-infrastructure career-web
git commit -m "feat: onboard verified Hangzhou district sources"
```

---

### Task 5: Expose geographic scope and distinguish access from document problems

**Files:**
- Modify: `career-application/src/main/java/com/careeros/application/AcquisitionPorts.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/JpaAcquisitionStore.java`
- Modify: `career-web/src/main/java/com/careeros/AcquisitionApiModels.java`
- Modify: `career-web/src/main/java/com/careeros/AcquisitionController.java`
- Modify: `career-web/src/test/java/com/careeros/AcquisitionApiTest.java`
- Modify: `career-ui/src/features/updates/updateApi.ts`
- Modify: `career-ui/src/features/updates/SourceList.tsx`
- Modify: `career-ui/src/features/updates/UpdatesPage.test.tsx`

**Interfaces:**
- Produces API fields `scopeLevel`, `scopeCode`, `priorityTier`, `coverageRole`, `accessStatus`, and `documentIssueCount`.
- Preserves `connectionStatus` for compatibility while displaying source access health separately from partial document processing.

- [ ] **Step 1: Write failing API and UI tests**

Assert an HTTPS-accessible source with one partial workbook displays “官网访问正常 · 1 个附件待处理” rather than “接入失败”. Assert district sources are grouped by `scopeCode` and show P0/P2 labels.

- [ ] **Step 2: Verify RED**

```powershell
mvn -pl career-web -am -Dtest=AcquisitionApiTest -Dsurefire.failIfNoSpecifiedTests=false test
Push-Location career-ui; npm test -- UpdatesPage.test.tsx; Pop-Location
```

- [ ] **Step 3: Implement API projection and UI presentation**

Compute access status from remote/contract run errors only. Compute document issue count from the failure ledger and partially processed documents. Do not derive either value from display text.

- [ ] **Step 4: Run tests and commit**

```powershell
mvn -pl career-application,career-infrastructure,career-web -am -Dtest=SourceConnectionProjectorTest,JpaAcquisitionStoreTest,AcquisitionApiTest -Dsurefire.failIfNoSpecifiedTests=false test
Push-Location career-ui; npm test -- UpdatesPage.test.tsx CareerPlanPage.test.tsx; Pop-Location
git add career-application career-infrastructure career-web career-ui
git commit -m "feat: expose truthful Hangzhou source health"
```

---

### Task 6: Run Hangzhou source acceptance and remove the real pseudo-job

**Files:**
- Create: `scripts/hangzhou_wave2_slice_a_acceptance.ps1`
- Modify: `README.md`
- Modify: `docs/current-gap-analysis.md`

**Interfaces:**
- Consumes the running PostgreSQL-backed application and official HTTPS endpoints.
- Produces exact source states, annual coverage, document failures, active pseudo-job count, idempotent deltas, and a measured list of sources still not connected.

- [ ] **Step 1: Write acceptance assertions**

The script must fail unless:

- all ten P0 districts exist in the target catalog;
- no source is `CONNECTED` solely because it is configured;
- no active repeated-header pseudo-job remains;
- two identical successful incremental runs produce zero second-run additions and updates;
- document issues and remote access failures are reported separately.

- [ ] **Step 2: Run full deterministic verification**

```powershell
$taskJdk=(Resolve-Path '.tooling\temurin-21\jdk-21.0.12+8').Path
$env:JAVA_HOME=$taskJdk
$env:Path="$taskJdk\bin;$env:Path"
mvn test
Push-Location career-ui; npm run verify; npm run build; Pop-Location
```

- [ ] **Step 3: Restart the application, apply migrations, and run accepted sources**

```powershell
powershell -File scripts/hangzhou_wave2_slice_a_acceptance.ps1 -BaseUrl http://localhost:8080 -RunSources
```

The corrected HZ HRSS snapshot must deactivate the existing pseudo-job without direct database deletion.

- [ ] **Step 4: Record measured results and commit**

Document connected/partial/failed/not-connected counts, annual completion, official job deltas, remaining source contracts, and exact test totals.

```powershell
git add scripts README.md docs/current-gap-analysis.md
git commit -m "test: verify Hangzhou source slice A"
git push origin codex/phase4b-career-workbench
```

---

## Plan Self-Review Traceability

| Spec requirement | Implementing task |
|---|---|
| Repeated header does not create a pseudo-job or source failure | 1, 6 |
| Ten P0 districts and three P2 counties are represented truthfully | 2, 6 |
| Site differences remain configuration/adapter concerns | 3, 4 |
| Only verified sources are enabled | 2, 4, 6 |
| 2024—2027 annual scope is explicit | 2, 4 |
| Registered, accessible, partial-document, and connected states are distinct | 2, 5, 6 |
| Same input is idempotent and remote failures do not create false data | 3, 4, 6 |
| Full backend/frontend/live acceptance | 4, 5, 6 |
