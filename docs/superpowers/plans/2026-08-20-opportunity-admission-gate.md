# Opportunity Admission Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Separate raw acquired jobs from verified career opportunities so unverified legacy data can never appear as T1/T2/T3 recommendations.

**Architecture:** Add a `JobAdmission` domain record and a one-to-one PostgreSQL admission table for every job. Database triggers initialize new or content-changed jobs as raw and needing review; decision assessment and ranking pass through an application-level admission gate. A candidate-independent summary API lets the UI explain how many records are raw, under review, verified, and ready for the opportunity pool.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Data JPA, PostgreSQL 16, Flyway, JUnit 5, AssertJ, Testcontainers, React 19, TypeScript, TanStack Query, Vitest, React Testing Library.

**Spec:** `docs/superpowers/specs/2026-08-20-correctness-evidence-core-loop-design.md`

## Global Constraints

- `docs/CAREER_OS_MASTER_SPEC.md` remains the authoritative business specification.
- Preserve all 2291 existing jobs, acquisition documents, artifacts, decisions, and user actions; do not delete or reset the database.
- Existing jobs are backfilled as `RAW` plus `NEEDS_REVIEW`; they are not inferred to be verified.
- Only `VERIFIED` plus `INCLUDED` jobs are admitted to decision assessment and T1/T2/T3 ranking.
- `UNKNOWN` employment identity must never be promoted to T3 by this slice.
- Domain and application modules must not depend on Spring or JPA.
- No Kafka, Kubernetes, Elasticsearch, microservices, workflow engine, new model provider, or browser crawler is added.
- Every behavioral change follows red-green-refactor and every task ends with focused tests and a commit.
- The local PostgreSQL volume is preserved; Flyway migrates it in place.

---

## File Map

- `career-domain/src/main/java/com/careeros/domain/JobAdmission.java`: immutable admission state and the single `admitted()` invariant.
- `career-domain/src/main/java/com/careeros/domain/DomainEnums.java`: admission statuses and explicit reason codes.
- `career-application/src/main/java/com/careeros/application/JobAdmissionPorts.java`: persistence and inventory contracts.
- `career-application/src/main/java/com/careeros/application/DecisionExceptions.java`: stable exception for direct assessment of an unadmitted job.
- `career-application/src/main/java/com/careeros/application/DecisionRankingService.java`: filters before assessment.
- `career-application/src/main/java/com/careeros/application/DecisionIntelligenceService.java`: blocks direct assessment before any snapshot is created.
- `career-application/src/main/java/com/careeros/application/JobLibrarySummaryService.java`: candidate-independent admission inventory.
- `career-infrastructure/src/main/resources/db/migration/V9__job_opportunity_admission.sql`: table, backfill, indexes, and insert/content-change triggers.
- `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/JpaModels.java`: JPA admission entity.
- `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/JobAdmissionJpaRepository.java`: admission queries and aggregate counts.
- `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/JpaJobAdmissionStore.java`: maps admission rows to application ports.
- `career-web/src/main/java/com/careeros/ApplicationConfiguration.java`: injects the gate and summary service.
- `career-web/src/main/java/com/careeros/JobLibrarySummaryController.java`: `GET /api/v1/job-library/summary`.
- `career-web/src/main/java/com/careeros/ApiExceptionHandler.java`: returns `409 JOB_NOT_ADMITTED`.
- `career-ui/src/features/updates/updateApi.ts`: typed summary request.
- `career-ui/src/features/updates/AdmissionSummary.tsx`: raw versus verified inventory.
- `career-ui/src/features/updates/UpdatesPage.tsx`: renders the inventory before source controls.
- `career-ui/src/features/opportunities/OpportunitiesPage.tsx`: loads the same summary for truthful empty states.
- `career-ui/src/features/opportunities/OpportunityQueue.tsx`: explains why an opportunity tier is empty.

---

### Task 1: Admission Domain and Ports

**Files:**
- Create: `career-domain/src/main/java/com/careeros/domain/JobAdmission.java`
- Modify: `career-domain/src/main/java/com/careeros/domain/DomainEnums.java`
- Create: `career-domain/src/test/java/com/careeros/domain/JobAdmissionTest.java`
- Create: `career-application/src/main/java/com/careeros/application/JobAdmissionPorts.java`

**Interfaces:**
- Produces: `JobAdmission(UUID jobPostingId, DataQualityStatus dataQualityStatus, TargetScopeStatus targetScopeStatus, Set<JobAdmissionReason> reasonCodes, String evaluatorVersion, Instant assessedAt, boolean humanVerified)`.
- Produces: `boolean JobAdmission.admitted()` which is true only for `VERIFIED` and `INCLUDED`.
- Produces: `JobAdmissionPorts.JobAdmissions.findByJobId(UUID)`, `save(JobAdmission)`, and `summarize()`.
- Produces: `AdmissionSummary(long total, Map<DataQualityStatus,Long> byQuality, Map<TargetScopeStatus,Long> byTargetScope, long opportunityReady)` with defensive immutable maps.

- [x] **Step 1: Write the failing domain tests**

```java
@Test
void onlyVerifiedIncludedJobsAreAdmitted() {
    assertThat(admission(DataQualityStatus.VERIFIED, TargetScopeStatus.INCLUDED).admitted()).isTrue();
    assertThat(admission(DataQualityStatus.RAW, TargetScopeStatus.INCLUDED).admitted()).isFalse();
    assertThat(admission(DataQualityStatus.VERIFIED, TargetScopeStatus.NEEDS_REVIEW).admitted()).isFalse();
    assertThat(admission(DataQualityStatus.VERIFIED, TargetScopeStatus.EXCLUDED).admitted()).isFalse();
}

@Test
void rawFactoryNeverClaimsHumanVerification() {
    JobAdmission value = JobAdmission.raw(JOB_ID, NOW, JobAdmissionReason.LEGACY_UNVERIFIED);
    assertThat(value.dataQualityStatus()).isEqualTo(DataQualityStatus.RAW);
    assertThat(value.targetScopeStatus()).isEqualTo(TargetScopeStatus.NEEDS_REVIEW);
    assertThat(value.humanVerified()).isFalse();
}
```

- [x] **Step 2: Run the tests and verify RED**

Run:

```powershell
mvn -pl career-domain -am '-Dtest=JobAdmissionTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: test compilation fails because `JobAdmission`, `TargetScopeStatus`, and `JobAdmissionReason` do not exist.

- [x] **Step 3: Implement the minimal domain types and port**

Add to `DomainEnums`:

```java
public enum TargetScopeStatus { INCLUDED, EXCLUDED, NEEDS_REVIEW }
public enum JobAdmissionReason {
    LEGACY_UNVERIFIED, NOT_CLASSIFIED, CONTENT_CHANGED, TARGET_TECHNICAL_ROLE,
    DOCTOR_REQUIRED, TEACHING_ROLE, POSTDOCTORAL_ROLE, ADMINISTRATIVE_ROLE,
    SALES_ROLE, LABOR_DISPATCH, PROJECT_BASED, INTERNSHIP,
    NON_TECHNICAL_ROLE, AMBIGUOUS_DUTIES
}
```

Implement `JobAdmission` with non-null validation, defensive `Set.copyOf`, `raw(...)`, and:

```java
public boolean admitted() {
    return dataQualityStatus == DataQualityStatus.VERIFIED
        && targetScopeStatus == TargetScopeStatus.INCLUDED;
}
```

Implement `JobAdmissionPorts` with the exact signatures in the Interfaces section. `AdmissionSummary` fills absent enum keys with zero when queried through `count(DataQualityStatus)` or `count(TargetScopeStatus)` helper methods.

- [x] **Step 4: Run focused domain and application compilation tests**

```powershell
mvn -pl career-application -am '-Dtest=JobAdmissionTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: `JobAdmissionTest` passes and the application module compiles.

- [x] **Step 5: Commit the domain contract**

```powershell
git add career-domain/src/main/java/com/careeros/domain/DomainEnums.java career-domain/src/main/java/com/careeros/domain/JobAdmission.java career-domain/src/test/java/com/careeros/domain/JobAdmissionTest.java career-application/src/main/java/com/careeros/application/JobAdmissionPorts.java
git commit -m "feat(admission): define verified opportunity gate"
```

---

### Task 2: PostgreSQL Admission Persistence and Safe Backfill

**Files:**
- Create: `career-infrastructure/src/main/resources/db/migration/V9__job_opportunity_admission.sql`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/JpaModels.java`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/JobAdmissionJpaRepository.java`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/JpaJobAdmissionStore.java`
- Modify: `career-infrastructure/src/test/java/com/careeros/infrastructure/MigrationIntegrationTest.java`
- Create: `career-infrastructure/src/test/java/com/careeros/infrastructure/persistence/JpaJobAdmissionStoreTest.java`

**Interfaces:**
- Consumes: `JobAdmissionPorts.JobAdmissions` and `JobAdmission` from Task 1.
- Produces: `JpaJobAdmissionStore implements JobAdmissions`.
- Produces: one `job_admission` row per `job_posting` and a database invariant that insert/content change returns the job to review.

- [x] **Step 1: Write failing migration and store tests**

Add a V8-to-V9 upgrade test that creates one legacy `job_posting`, migrates to V9, and asserts:

```java
assertThat(row.getString("data_quality_status")).isEqualTo("RAW");
assertThat(row.getString("target_scope_status")).isEqualTo("NEEDS_REVIEW");
assertThat(row.getString("reason_codes")).contains("LEGACY_UNVERIFIED");
assertThat(row.getBoolean("human_verified")).isFalse();
```

Add a persistence test which saves `VERIFIED + INCLUDED`, reloads it, and verifies `summarize().opportunityReady() == 1`.

- [x] **Step 2: Run the infrastructure tests and verify RED**

```powershell
mvn -pl career-infrastructure -am '-Dtest=MigrationIntegrationTest,JpaJobAdmissionStoreTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: migration count and admission persistence assertions fail because V9 and its repository do not exist.

- [x] **Step 3: Implement V9 and JPA mapping**

Create `job_admission` with:

```sql
CREATE TABLE job_admission (
    job_posting_id UUID PRIMARY KEY REFERENCES job_posting(id) ON DELETE CASCADE,
    data_quality_status TEXT NOT NULL CHECK (data_quality_status IN ('RAW','PARSED','NORMALIZED','REVIEW_REQUIRED','VERIFIED','REJECTED','FAILED')),
    target_scope_status TEXT NOT NULL CHECK (target_scope_status IN ('INCLUDED','EXCLUDED','NEEDS_REVIEW')),
    reason_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    evaluator_version TEXT NOT NULL,
    assessed_at TIMESTAMPTZ NOT NULL,
    human_verified BOOLEAN NOT NULL DEFAULT FALSE
);
```

Backfill every existing job as `RAW`, `NEEDS_REVIEW`, `['LEGACY_UNVERIFIED']`, `admission-v1`, and `human_verified=false`. Add indexes on `(data_quality_status, target_scope_status)` and `target_scope_status`.

Add an `AFTER INSERT` trigger that creates `RAW / NEEDS_REVIEW / NOT_CLASSIFIED`. Add an `AFTER UPDATE OF content_fingerprint` trigger guarded by `OLD.content_fingerprint IS DISTINCT FROM NEW.content_fingerprint`; it resets the admission to `RAW / NEEDS_REVIEW / CONTENT_CHANGED` and `human_verified=false`. Both functions use `INSERT ... ON CONFLICT` so replay is idempotent.

Map `reason_codes` with `@JdbcTypeCode(SqlTypes.JSON)` and implement repository count queries. `JpaJobAdmissionStore.summarize()` must return enum-keyed maps and calculate readiness with a repository query for `VERIFIED + INCLUDED` rather than loading all rows.

- [x] **Step 4: Run migration and persistence tests GREEN**

```powershell
mvn -pl career-infrastructure -am '-Dtest=MigrationIntegrationTest,JpaJobAdmissionStoreTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: fresh migration, V8 upgrade, trigger, round-trip, and aggregate tests pass.

- [x] **Step 5: Commit persistence**

```powershell
git add career-infrastructure/src/main/resources/db/migration/V9__job_opportunity_admission.sql career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/JpaModels.java career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/JobAdmissionJpaRepository.java career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/JpaJobAdmissionStore.java career-infrastructure/src/test/java/com/careeros/infrastructure/MigrationIntegrationTest.java career-infrastructure/src/test/java/com/careeros/infrastructure/persistence/JpaJobAdmissionStoreTest.java
git commit -m "feat(admission): persist raw and verified job states"
```

---

### Task 3: Enforce Admission Before Decisions

**Files:**
- Modify: `career-application/src/main/java/com/careeros/application/DecisionRankingService.java`
- Modify: `career-application/src/main/java/com/careeros/application/DecisionIntelligenceService.java`
- Modify: `career-application/src/main/java/com/careeros/application/DecisionExceptions.java`
- Modify: `career-application/src/test/java/com/careeros/application/DecisionRankingAndExplanationTest.java`
- Modify: `career-application/src/test/java/com/careeros/application/DecisionIntelligenceServiceTest.java`
- Modify: `career-web/src/main/java/com/careeros/ApplicationConfiguration.java`
- Modify: `career-web/src/main/java/com/careeros/ApiExceptionHandler.java`
- Modify: `career-web/src/test/java/com/careeros/DecisionApiTest.java`

**Interfaces:**
- Consumes: `JobAdmissions.findByJobId(UUID)` from Task 1.
- Produces: `DecisionExceptions.JobNotAdmittedException`.
- Produces: new constructors `DecisionRankingService(JobContexts, JobAdmissions, DecisionAssessor)` and `DecisionIntelligenceService(..., JobAdmissions, ...)`.
- Produces: HTTP `409` with Problem Detail code `JOB_NOT_ADMITTED` for direct assessment of raw/review jobs.

- [x] **Step 1: Write failing gate tests**

Add a ranking test with one admitted and one raw active job:

```java
assertThat(page.items()).extracting(item -> item.jobContext().job().id())
    .containsExactly(admittedJobId);
verify(assessor, never()).assess(any(), eq(rawJobId), any());
```

Add a direct-assessment test:

```java
assertThatThrownBy(() -> service.assess(CANDIDATE_ID, RAW_JOB_ID, NOW))
    .isInstanceOf(JobNotAdmittedException.class);
assertThat(snapshots.values).isEmpty();
```

Add an API test expecting `409` and `$.code == "JOB_NOT_ADMITTED"`.

- [x] **Step 2: Run focused tests and verify RED**

```powershell
mvn -pl career-web -am '-Dtest=DecisionRankingAndExplanationTest,DecisionIntelligenceServiceTest,DecisionApiTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: constructor/signature compilation failures and missing exception mapping.

- [x] **Step 3: Implement the gate**

In ranking, filter active contexts before calling the assessor:

```java
.filter(context -> admissions.findByJobId(context.job().id())
    .map(JobAdmission::admitted)
    .orElse(false))
```

In direct assessment, call a private `requireAdmitted(jobId)` before snapshot lookup, evaluator calls, or persistence. Missing admission and non-admitted admission both throw `JobNotAdmittedException("Job is not verified for opportunity decisions: " + jobId)`; `JobNotFoundException` remains reserved for a missing job.

Map this exception to HTTP 409 with code `JOB_NOT_ADMITTED`, title `Job not admitted`, and a plain detail. Wire the shared `JpaJobAdmissionStore` through `ApplicationConfiguration`.

- [x] **Step 4: Run decision tests GREEN**

```powershell
mvn -pl career-web -am '-Dtest=DecisionRankingAndExplanationTest,DecisionIntelligenceServiceTest,DecisionApiTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: raw jobs are never assessed, admitted jobs retain existing ordering, and direct raw assessment returns the stable 409 problem.

- [x] **Step 5: Commit the decision gate**

```powershell
git add career-application/src/main/java/com/careeros/application/DecisionRankingService.java career-application/src/main/java/com/careeros/application/DecisionIntelligenceService.java career-application/src/main/java/com/careeros/application/DecisionExceptions.java career-application/src/test/java/com/careeros/application/DecisionRankingAndExplanationTest.java career-application/src/test/java/com/careeros/application/DecisionIntelligenceServiceTest.java career-web/src/main/java/com/careeros/ApplicationConfiguration.java career-web/src/main/java/com/careeros/ApiExceptionHandler.java career-web/src/test/java/com/careeros/DecisionApiTest.java
git commit -m "fix(decision): exclude unverified jobs from opportunities"
```

---

### Task 4: Job Library Admission Summary API

**Files:**
- Create: `career-application/src/main/java/com/careeros/application/JobLibrarySummaryService.java`
- Create: `career-application/src/test/java/com/careeros/application/JobLibrarySummaryServiceTest.java`
- Create: `career-web/src/main/java/com/careeros/JobLibrarySummaryController.java`
- Create: `career-web/src/test/java/com/careeros/JobLibrarySummaryApiTest.java`
- Modify: `career-web/src/main/java/com/careeros/ApplicationConfiguration.java`

**Interfaces:**
- Consumes: `JobAdmissions.summarize()`.
- Produces: `GET /api/v1/job-library/summary`.
- Produces JSON fields `total`, `raw`, `parsed`, `normalized`, `reviewRequired`, `verified`, `rejected`, `failed`, `included`, `excluded`, `needsReview`, and `opportunityReady`.

- [x] **Step 1: Write failing service and controller tests**

Use an inventory fixture with `total=2291`, `raw=2291`, `needsReview=2291`, `opportunityReady=0`, then assert the service and JSON preserve those exact counts.

```java
mvc.perform(get("/api/v1/job-library/summary"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.total").value(2291))
    .andExpect(jsonPath("$.raw").value(2291))
    .andExpect(jsonPath("$.needsReview").value(2291))
    .andExpect(jsonPath("$.opportunityReady").value(0));
```

- [x] **Step 2: Run tests and verify RED**

```powershell
mvn -pl career-web -am '-Dtest=JobLibrarySummaryServiceTest,JobLibrarySummaryApiTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: missing service/controller compilation failures.

- [x] **Step 3: Implement the read model and endpoint**

Implement `JobLibrarySummaryService.load()` as a pure mapping from enum maps to the explicit response record. Keep zero-valued fields in JSON so the UI never guesses missing counters. The controller contains only `@GetMapping` and delegates to `load()`.

- [x] **Step 4: Run API tests GREEN**

```powershell
mvn -pl career-web -am '-Dtest=JobLibrarySummaryServiceTest,JobLibrarySummaryApiTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: stable summary response passes with all fields present.

- [x] **Step 5: Commit summary API**

```powershell
git add career-application/src/main/java/com/careeros/application/JobLibrarySummaryService.java career-application/src/test/java/com/careeros/application/JobLibrarySummaryServiceTest.java career-web/src/main/java/com/careeros/JobLibrarySummaryController.java career-web/src/test/java/com/careeros/JobLibrarySummaryApiTest.java career-web/src/main/java/com/careeros/ApplicationConfiguration.java
git commit -m "feat(admission): expose job library readiness summary"
```

---

### Task 5: Truthful Opportunity and Update Screens

**Files:**
- Modify: `career-ui/src/api/contracts.ts`
- Modify: `career-ui/src/api/http.ts`
- Modify: `career-ui/src/features/updates/updateApi.ts`
- Create: `career-ui/src/features/updates/AdmissionSummary.tsx`
- Modify: `career-ui/src/features/updates/UpdatesPage.tsx`
- Modify: `career-ui/src/features/updates/UpdatesPage.test.tsx`
- Modify: `career-ui/src/features/opportunities/OpportunitiesPage.tsx`
- Modify: `career-ui/src/features/opportunities/OpportunityQueue.tsx`
- Modify: `career-ui/src/features/opportunities/OpportunitiesPage.test.tsx`
- Modify: `career-ui/src/styles/updates.css`
- Modify: `career-ui/src/styles/opportunities.css`

**Interfaces:**
- Consumes: `GET /api/v1/job-library/summary` from Task 4.
- Produces: `JobLibrarySummary` TypeScript type with the exact response fields.
- Produces: update-page inventory and opportunity empty-state copy based on server-authoritative counts.

- [ ] **Step 1: Write failing UI tests**

Add a summary response fixture:

```ts
const admissionSummary = {
  total: 2291, raw: 2291, parsed: 0, normalized: 0,
  reviewRequired: 0, verified: 0, rejected: 0, failed: 0,
  included: 0, excluded: 0, needsReview: 2291, opportunityReady: 0,
}
```

Assert the Updates page shows `2291 条原始记录`, `0 个可信机会`, and `2291 条等待分类或证据复核`. Assert an empty T1 queue says `目前没有通过证据准入的可信岗位` and `原始岗位不会自动进入 T1/T2/T3` rather than the old generic empty copy.

- [ ] **Step 2: Run Vitest and verify RED**

```powershell
Set-Location career-ui
npm test -- --run src/features/updates/UpdatesPage.test.tsx src/features/opportunities/OpportunitiesPage.test.tsx
```

Expected: missing summary request/component and missing truthful copy.

- [ ] **Step 3: Implement typed summary UI**

Add `queryKeys.jobLibrarySummary`, `getJobLibrarySummary()`, and parallel TanStack queries on Updates and Opportunities pages. `AdmissionSummary` renders four semantic values: total raw library, review-needed, verified, and opportunity-ready. It must label these as data states, not progress percentages.

Change `OpportunityQueue` to accept `admissionSummary?: JobLibrarySummary`. When `opportunityReady == 0`, render:

```text
目前没有通过证据准入的可信岗位
岗位库中的原始记录仍在，但不会自动进入 T1/T2/T3。请先完成目标岗位、用工身份和资格证据复核。
```

When opportunity-ready jobs exist but the selected tier is empty, retain the tier-specific empty message.

- [ ] **Step 4: Run frontend tests and typecheck GREEN**

```powershell
npm run verify
```

Expected: all frontend tests and TypeScript compilation pass.

- [ ] **Step 5: Commit truthful UI**

```powershell
Set-Location ..
git add career-ui/src/api/contracts.ts career-ui/src/api/http.ts career-ui/src/features/updates/updateApi.ts career-ui/src/features/updates/AdmissionSummary.tsx career-ui/src/features/updates/UpdatesPage.tsx career-ui/src/features/updates/UpdatesPage.test.tsx career-ui/src/features/opportunities/OpportunitiesPage.tsx career-ui/src/features/opportunities/OpportunityQueue.tsx career-ui/src/features/opportunities/OpportunitiesPage.test.tsx career-ui/src/styles/updates.css career-ui/src/styles/opportunities.css
git commit -m "fix(ui): distinguish raw jobs from trusted opportunities"
```

---

### Task 6: End-to-End Upgrade, Documentation, and Real Data Acceptance

**Files:**
- Modify: `career-web/src/test/java/com/careeros/CareerOsApplicationTest.java`
- Modify: `README.md`
- Modify: `docs/current-gap-analysis.md`
- Modify: `docs/PHASE4B_WORKBENCH.md`

**Interfaces:**
- Consumes: V9 migration, gate, summary API, and UI from Tasks 1–5.
- Produces: an upgrade-safe local runtime where the current database reports `2291 raw`, `2291 needs review`, and `0 opportunity ready` until later verification work promotes jobs.

- [ ] **Step 1: Add an end-to-end gate assertion**

In the Spring Boot integration test, insert one raw and one manually saved verified/included admission. Assert ranking returns only the verified job and summary counts both records correctly. Assert a direct POST assessment for the raw job returns `409 JOB_NOT_ADMITTED`.

- [ ] **Step 2: Run the full test suite before documentation**

```powershell
mvn test
```

Expected: every Java, migration, API, frontend type, and frontend component test passes.

- [ ] **Step 3: Correct product-status documentation**

Change README and Phase 4B wording from “current mainline completed” to:

```text
Engineering foundation is operational. Business MVP correctness is under remediation.
Raw acquired records are not trusted opportunities until admission verification succeeds.
```

Update `current-gap-analysis.md` with V9 admission-gate status and keep candidate facts, field evidence, employment identity, Golden Jobs, and Eligibility Agent listed as still pending. Do not claim the overall business MVP is complete.

- [ ] **Step 4: Migrate and verify the preserved local database**

Run:

```powershell
pwsh -NoProfile -File scripts/Test-LocalRuntime.ps1 -Smoke
Invoke-RestMethod http://localhost:8080/api/v1/job-library/summary | ConvertTo-Json
Invoke-RestMethod 'http://localhost:8080/api/v1/candidates/01992f09-0000-7000-8000-000000000001/job-decisions?tier=T3&page=0&size=20' | ConvertTo-Json
```

Expected on the current preserved database:

```text
total = 2291
raw = 2291
needsReview = 2291
opportunityReady = 0
T3 total = 0
health = UP
```

Open `/updates` and `/opportunities` with Playwright. Verify the raw/verified distinction is visible, T3 no longer shows 2082 uncertain jobs, there are no console errors, and save screenshots under ignored `output/playwright/`.

- [ ] **Step 5: Final review, commit, and private push**

```powershell
git diff --check
git add README.md docs/current-gap-analysis.md docs/PHASE4B_WORKBENCH.md career-web/src/test/java/com/careeros/CareerOsApplicationTest.java
git commit -m "docs: mark business opportunity admission honestly"
git push origin codex/phase4b-career-workbench
gh repo view career-os --json visibility,url
```

Expected: clean worktree, private visibility, local health UP, and the remote branch at the final commit.

---

## Completion Boundary

Completing this plan does not claim the whole corrected business MVP is done. It establishes the non-negotiable gate that all later work depends on. The next plan is `candidate-confirmed-facts`; after that come `employment-identity-and-field-evidence`, `golden-job-qualification`, and `eligibility-explanation-agent`.
