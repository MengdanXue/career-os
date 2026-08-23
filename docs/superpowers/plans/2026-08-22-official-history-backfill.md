# Official History Backfill Implementation Plan

> **For Codex:** Execute this plan task-by-task with strict red-green-refactor cycles. Do not mark a source-year complete unless the official listing's reported total has been traversed and every discovered target document has been handled successfully.

**Goal:** Backfill 2024–2026 recruitment announcements and official attachments from Zhejiang HRSS and Hangzhou HRSS through the existing incremental acquisition pipeline, while maintaining truthful source-year coverage.

**Architecture:** Add a bounded historical-run path to `AcquisitionService`. It will paginate the two official JCMS listings using their reported totals, select requested recruitment years, reuse the existing immutable document/change/import pipeline, and persist a coverage result per source-year. A dedicated HTTP endpoint triggers one source and a year range. Existing scheduled current acquisition remains unchanged.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL, Flyway, JUnit 5, AssertJ, Mockito, existing HTML/Excel/PDF processors.

---

### Task 1: Prove official pagination and year selection behavior

**Files:**
- Modify: `career-application/src/test/java/com/careeros/application/AcquisitionServiceTest.java`
- Modify: `career-application/src/main/java/com/careeros/application/AcquisitionService.java`

1. Add a failing service test whose official listing reports more than one page and contains 2024, 2025, and 2026 links.
2. Assert that a 2024–2026 backfill requests every required listing page exactly once, deduplicates announcements, and ignores links outside the requested years.
3. Run the focused test and confirm it fails because the historical API is absent.
4. Implement the minimum bounded JCMS pagination and year-selection path.
5. Re-run the focused test and keep existing current-run tests green.

### Task 2: Persist truthful source-year coverage

**Files:**
- Modify: `career-application/src/test/java/com/careeros/application/AcquisitionServiceTest.java`
- Modify: `career-application/src/main/java/com/careeros/application/AcquisitionPorts.java`
- Modify: `career-application/src/main/java/com/careeros/application/AcquisitionService.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/JpaAcquisitionStore.java`
- Modify: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/JpaAcquisitionStoreTest.java`

1. Add failing tests for `COMPLETE`, `NO_TARGET_RECORDS`, `PARTIAL`, and `ACCESS_FAILED` transitions.
2. Assert that only a fully traversed official index plus successful document handling receives a completed status and completion basis.
3. Add a store query that counts active technical target jobs for one source-year by joining the source's acquired announcement URLs to active jobs.
4. Implement coverage updates before fetch, after discovery, on failure, and at terminal completion.
5. Re-run application and PostgreSQL store tests.

### Task 3: Expose a bounded historical-run API

**Files:**
- Modify: `career-web/src/test/java/com/careeros/AcquisitionApiTest.java`
- Modify: `career-web/src/main/java/com/careeros/AcquisitionController.java`
- Modify: `career-web/src/main/java/com/careeros/AcquisitionApiModels.java`

1. Add a failing MVC test for `POST /api/acquisition/sources/{sourceId}/historical-runs?fromYear=2024&toYear=2026`.
2. Assert validation rejects reversed ranges and years outside 2000–2100.
3. Implement the endpoint and return the crawl run plus the resulting coverage rows.
4. Re-run web API tests.

### Task 4: Configure the two official sources for bounded full traversal

**Files:**
- Create: `career-infrastructure/src/main/resources/db/migration/V19__configure_historical_official_pagination.sql`
- Modify: `career-infrastructure/src/test/java/com/careeros/infrastructure/MigrationIntegrationTest.java`

1. Add a failing migration assertion for the historical pagination mode, page size, and safety cap on both official sources.
2. Add an append-only Flyway migration configuring JCMS `paramJson` pagination with page size 100 and a conservative maximum page cap.
3. Verify migration checks and fresh/upgrade database behavior.

### Task 5: Verify repeatability and run real backfill

**Files:**
- Modify only if a defect is revealed by verification.

1. Run focused Java tests for acquisition application, infrastructure, and web modules.
2. Run the complete Maven and frontend suites.
3. Start the local system, trigger 2024–2026 historical runs for Zhejiang HRSS and Hangzhou HRSS, and inspect coverage, run counters, changes, and representative imported jobs.
4. Re-run the same historical jobs and confirm no duplicate change/job records are created.
5. Keep any source-year with partial fetch/parse results as `PARTIAL` or `ACCESS_FAILED`; never convert the failure into zero jobs.
6. Commit and push the verified change to the existing private repository.
