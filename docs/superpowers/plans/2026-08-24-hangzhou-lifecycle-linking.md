# Hangzhou Recruitment Lifecycle Linking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist Hangzhou recruitment lifecycle announcements and conservatively link them to the original recruitment event.

**Architecture:** A pure domain classifier identifies one or more lifecycle stages and derives a normalized campaign stem. A JDBC persistence service performs idempotent stage-row upserts and updates an event only when matching yields exactly one candidate. The existing Phase 2 processor routes lifecycle documents before normal recruitment-event upsert, while source health exposes lifecycle linkage counts.

**Tech Stack:** Java 21, Spring Boot 3.5, JdbcTemplate, PostgreSQL 16, Flyway, JUnit 5, AssertJ, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-08-24-hangzhou-lifecycle-linking-design.md`

## Global Constraints

- Matching is deterministic; LLM output cannot select a recruitment event.
- Only a unique candidate may update an event.
- Unmatched and ambiguous documents remain persisted and auditable.
- One official URL may represent multiple lifecycle stages.
- Official HTTPS URLs and evidence IDs are retained.

---

### Task 1: Lifecycle title classification

**Files:**
- Create: `career-domain/src/main/java/com/careeros/domain/RecruitmentLifecycle.java`
- Test: `career-domain/src/test/java/com/careeros/domain/RecruitmentLifecycleTest.java`

**Interfaces:**
- Produces: `RecruitmentLifecycle.classify(String): Set<Stage>` and `RecruitmentLifecycle.campaignStem(String): String`.

- [x] Write tests for qualification review, score, interview, physical exam plus investigation, publication, appointment, multi-stage titles, and ordinary recruitment notices.
- [x] Run `mvn -q -pl career-domain -Dtest=RecruitmentLifecycleTest test` and confirm compilation/test failure because the type does not exist.
- [x] Implement the enum, ordered keyword patterns and conservative campaign-stem normalization.
- [x] Re-run the focused test and confirm it passes.
- [x] Commit the domain classifier.

### Task 2: Idempotent lifecycle persistence and unique matching

**Files:**
- Create: `career-infrastructure/src/main/resources/db/migration/V38__recruitment_lifecycle_documents.sql`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/OfficialLifecycleDocumentService.java`
- Create: `career-infrastructure/src/test/java/com/careeros/infrastructure/persistence/OfficialLifecycleDocumentServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `RecruitmentLifecycle.classify` and `campaignStem`.
- Produces: `recordIfLifecycle(String title, String sourceUrl, int year, LocalDate publishedOn, UUID evidenceId): Optional<Result>`.

- [x] Write Testcontainers tests proving unique match, idempotent replay, unmatched retention, ambiguous retention, and multi-stage updates.
- [x] Run the focused integration test and confirm failure because migration/service are absent.
- [x] Add the table with `UNIQUE(source_url, stage)`, matching-status checks, evidence/event foreign keys and audit timestamps.
- [x] Implement candidate loading, deterministic matching, per-stage upsert and event-stage updates.
- [x] Re-run the focused integration test and confirm it passes.
- [x] Commit persistence and matching.

### Task 3: Route lifecycle documents through acquisition

**Files:**
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/Phase2DocumentProcessor.java`
- Modify: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/Phase2DocumentProcessorTest.java`
- Create: `career-infrastructure/src/main/resources/db/migration/V39__enable_xihu_lifecycle_discovery.sql`
- Modify: `career-infrastructure/src/main/resources/official-source-catalog.yml`

**Interfaces:**
- Consumes: `OfficialLifecycleDocumentService.recordIfLifecycle`.
- Preserves: existing workbook, ordinary announcement and hospital processing.

- [x] Add a processor test showing a lifecycle HTML page is recorded and ordinary announcement upsert is not invoked.
- [x] Run the focused processor test and confirm it fails before production changes.
- [x] Inject the lifecycle service and route classified documents immediately after evidence extraction.
- [x] Change only the Xihu source exclusion rule so lifecycle titles are discovered, in both catalog and V39 runtime configuration.
- [x] Re-run processor, catalog and acquisition tests.
- [x] Commit acquisition routing.

### Task 4: Source-health lifecycle counts

**Files:**
- Modify: `career-application/src/main/java/com/careeros/application/AcquisitionPorts.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/JpaAcquisitionStore.java`
- Modify: `career-web/src/main/java/com/careeros/web/AcquisitionController.java`
- Modify: associated application/infrastructure/web tests.

**Interfaces:**
- Produces source response fields: `lifecycleDocumentCount`, `matchedLifecycleCount`, `unmatchedLifecycleCount`, `ambiguousLifecycleCount`.

- [x] Add API and repository tests for configured and unconfigured targets.
- [x] Run focused tests and confirm the new fields are missing.
- [x] Add a compact projection query grouped through `acquired_document.source_id` and lifecycle source URL.
- [x] Extend the source response without changing existing access/document issue semantics.
- [x] Re-run focused tests and commit.

### Task 5: Live acceptance and documentation

**Files:**
- Create: `scripts/hangzhou_lifecycle_acceptance.ps1`
- Modify: `README.md`
- Modify: `docs/current-gap-analysis.md`

**Interfaces:**
- Consumes: live source API and PostgreSQL lifecycle rows.
- Produces: repeatable acceptance output with document/linkage counts and pseudo-event count.

- [x] Add assertions that lifecycle rows are unique, every matched row has an event, no unmatched row updates an event, and no lifecycle title becomes a standalone recruitment event.
- [x] Run focused module tests, `mvn -q test`, frontend tests and build.
- [x] Rebuild/start the app, run Xihu twice, and execute the lifecycle acceptance script.
- [x] Record only measured counts in README and gap analysis.
- [x] Commit, push the private branch, and verify a clean worktree.
