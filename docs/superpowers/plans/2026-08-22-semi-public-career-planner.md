# Semi-Public Career Planner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a deterministic `/plan` workbench that turns the 2024–2026 official job history and the candidate's 2027 graduation timeline into evidence-backed routes, age windows, recruitment timing, risks, and actions.

**Architecture:** Extend the candidate aggregate with explicit gender, political affiliation, and evidence-aware employment records. Add an application-layer planning service over a narrow historical data port, implement the port with PostgreSQL read queries, expose one versioned REST endpoint, and render a read-only React planning page that drills into existing opportunity details.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Data JPA/JdbcTemplate, PostgreSQL 16, Flyway, React 19, TypeScript 7, TanStack Query, Vitest, JUnit 5, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-08-22-semi-public-career-planner-design.md`

## Global Constraints

- Hard eligibility, age, education, verification status, and experience duration are deterministic; an LLM never overrides them.
- Unknown facts remain unknown and never default to pass or fail.
- Historical support is shown as counts and evidence strength, never as admission probability.
- Existing job detail is the single source of truth for official facts and evidence.
- No new AI framework, search engine, queue, workflow engine, or public sharing feature.
- Every task follows red-green-refactor and references one or more requirement IDs from the spec.

---

### Task 1: Evidence-Aware Candidate Facts

**Requirements:** `REQ-AGE-001`, `REQ-EDU-001`, `REQ-EXP-001`

**Files:**
- Create: `career-domain/src/main/java/com/careeros/domain/CandidateEmploymentRecord.java`
- Modify: `career-domain/src/main/java/com/careeros/domain/DomainEnums.java`
- Modify: `career-domain/src/main/java/com/careeros/domain/CandidateProfile.java`
- Modify: `career-domain/src/main/java/com/careeros/domain/CandidateFacts.java`
- Test: `career-domain/src/test/java/com/careeros/domain/CandidatePlanningFactsTest.java`
- Create: `career-infrastructure/src/main/resources/db/migration/V20__candidate_planning_facts.sql`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/JpaModels.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/PersistenceAdaptersConfiguration.java`
- Modify: `career-web/src/main/java/com/careeros/CareerMvpController.java`
- Test: `career-infrastructure/src/test/java/com/careeros/infrastructure/MigrationIntegrationTest.java`
- Test: `career-web/src/test/java/com/careeros/CandidatePlanningProfileApiTest.java`

**Interfaces:**
- Produces `Gender { FEMALE, MALE, OTHER, UNKNOWN }` and `PoliticalAffiliation { CPC_MEMBER, CPC_PROBATIONARY, NON_MEMBER, UNKNOWN }`.
- Produces `CandidateEmploymentRecord(String employerName, String roleTitle, LocalDate startsOn, LocalDate endsOn, EmploymentMode employmentMode, VerificationStatus verificationStatus, Set<String> evidenceTypes)`.
- Extends `CandidateProfile` with `gender`, `politicalAffiliation`, and `employmentRecords` while retaining backward-compatible constructors that default to `UNKNOWN` and an empty list.
- Adds fact keys `GENDER`, `POLITICAL_AFFILIATION`, and `EMPLOYMENT_HISTORY`.

- [ ] **Step 1: Write failing domain tests**

Add tests proving blank employers are rejected, an end date before the start date is rejected, collections are immutable, and fingerprints change when gender or employment history changes:

```java
@Test void employmentHistoryIsAFirstClassConfirmedFact() {
    var candidate = candidateWith(Gender.FEMALE, List.of(verifiedEmployment()));
    assertThat(CandidateFacts.fingerprint(candidate, CandidateFactKey.GENDER)).isNotBlank();
    assertThat(CandidateFacts.resolve(candidate, List.of()).status(CandidateFactKey.EMPLOYMENT_HISTORY))
        .isEqualTo(CandidateFactStatus.UNCONFIRMED);
}
```

- [ ] **Step 2: Run the domain test and verify RED**

Run: `mvn -pl career-domain -Dtest=CandidatePlanningFactsTest test`

Expected: compilation fails because the planning enums and employment record do not exist.

- [ ] **Step 3: Implement the domain types and profile compatibility constructors**

Use these exact enums inside `CandidateEmploymentRecord`:

```java
public enum EmploymentMode { FULL_TIME, PART_TIME, INTERNSHIP, UNKNOWN }
public enum VerificationStatus { UNVERIFIED, PARTIAL, VERIFIED, REJECTED }
```

Normalize optional evidence types, require employer, role, start date, and verification status, and preserve `endsOn == null` for an open interval. Update `CandidateFacts.fingerprint`, `hasRecordedValue`, `HARD_QUALIFICATION_KEYS`, and `CandidateProfileService.withVersion` for the new values.

- [ ] **Step 4: Run domain tests and verify GREEN**

Run: `mvn -pl career-domain test`

Expected: all career-domain tests pass.

- [ ] **Step 5: Write failing migration and profile API tests**

Extend the migration test's expected Flyway count to 20. Add an API test that serializes and updates `gender`, `politicalAffiliation`, exact birth day 29, and one employment record without exposing sensitive document content.

- [ ] **Step 6: Run persistence/web tests and verify RED**

Run: `mvn -pl career-infrastructure,career-web -am -Dtest=MigrationIntegrationTest,CandidatePlanningProfileApiTest test`

Expected: the migration count and JSON fields fail because V20 and JPA mappings are absent.

- [ ] **Step 7: Add V20 and persistence mappings**

V20 must:

```sql
ALTER TABLE candidate_profile ADD COLUMN gender varchar(24) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE candidate_profile ADD COLUMN political_affiliation varchar(32) NOT NULL DEFAULT 'UNKNOWN';
UPDATE candidate_profile SET birth_day=29, gender='FEMALE'
WHERE id='01992f09-0000-7000-8000-000000000001';

INSERT INTO candidate_fact_confirmation (
    candidate_profile_id, fact_key, status, value_fingerprint, source, confirmed_at, updated_at
) VALUES
('01992f09-0000-7000-8000-000000000001', 'BIRTH_DATE', 'CONFIRMED',
 '07669bec3754ffe16a8bdc5cf5756c7793e4137904f27a444ea525ab88fb0d0a',
 'USER_CONFIRMED', now(), now()),
('01992f09-0000-7000-8000-000000000001', 'GENDER', 'CONFIRMED',
 'cf112cb65cc0fbbbd85eeaa20d3ac834bd954e7539ad8008b6487dbe47edb61f',
 'USER_CONFIRMED', now(), now())
ON CONFLICT (candidate_profile_id, fact_key) DO UPDATE SET
    status=excluded.status,
    value_fingerprint=excluded.value_fingerprint,
    source=excluded.source,
    confirmed_at=excluded.confirmed_at,
    updated_at=excluded.updated_at;
```

Create `candidate_employment_record` as an ordered element collection with checks for dates and enums. Replace the candidate fact-key check constraint with the complete old-and-new key list. Do not seed uncertain employment dates. Map the new fields in both JPA directions and accept them in `CandidateRequest`.

- [ ] **Step 8: Run migration/profile tests and verify GREEN**

Run: `mvn -pl career-infrastructure,career-web -am -Dtest=MigrationIntegrationTest,CandidatePlanningProfileApiTest test`

Expected: both tests pass.

- [ ] **Step 9: Commit Task 1**

```text
git add career-domain career-infrastructure career-web
git commit -m "feat: model candidate planning facts"
```

---

### Task 2: Deterministic Career Planning Service

**Requirements:** `REQ-PLAN-001`, `REQ-PLAN-002`, `REQ-PLAN-003`, `REQ-PLAN-005`, `REQ-AGE-001`, `REQ-CALENDAR-001`, `REQ-DEGRADE-001`

**Files:**
- Create: `career-application/src/main/java/com/careeros/application/planning/CareerPlanPorts.java`
- Create: `career-application/src/main/java/com/careeros/application/planning/CareerPlan.java`
- Create: `career-application/src/main/java/com/careeros/application/planning/CareerPlanService.java`
- Test: `career-application/src/test/java/com/careeros/application/planning/CareerPlanServiceTest.java`

**Interfaces:**
- `CareerPlanPorts.CareerPlanQuery` is a functional interface with `CareerPlanData load(UUID candidateId, int fromYear, int toYear, LocalDate asOf)`.
- `CareerPlanService.generate(UUID candidateId, int targetYear, LocalDate asOf)` returns `CareerPlan`.
- `CareerPlanData` contains `CandidateProfile`, target `HistoricalJob` rows, `CoverageSignal` rows, and `loadedAt`.
- `HistoricalJob` contains job/event IDs, year, event dates, exam subjects, organization name/type, title/family, employment type, education, age, experience, titles, candidate scope, requirements, source URL, and evidence coverage.

- [ ] **Step 1: Write failing service tests for the three scenarios**

Create fixtures for a bachelor-plus-title role, a master information-management role, an applicant-only/party-member role, and a row with incomplete source coverage. Assert:

```java
assertThat(plan.currentScenario().code()).isEqualTo("PRE_GRADUATION");
assertThat(plan.futureScenarios()).extracting(Scenario::code)
    .containsExactly("DEGREE_PENDING_VERIFICATION", "MASTER_VERIFIED");
assertThat(plan.recommendedRoutes().getFirst().code()).isEqualTo("PUBLIC_TECH");
assertThat(plan.recommendedRoutes().getFirst().label()).doesNotContain("概率");
```

Add boundary assertions for 1992-12-31 at representative spring dates: age 35 passes in spring 2028, age 35 fails in spring 2029, and age 38 passes in spring 2031.

- [ ] **Step 2: Run service tests and verify RED**

Run: `mvn -pl career-application -am -Dtest=CareerPlanServiceTest test`

Expected: compilation fails because planning types do not exist.

- [ ] **Step 3: Implement immutable planning records**

`CareerPlan` must expose:

```java
UUID candidateId;
int targetYear;
LocalDate asOf;
CandidateSnapshot candidateSnapshot;
Scenario currentScenario;
List<Scenario> futureScenarios;
List<Route> recommendedRoutes;
List<AgeWindow> ageWindows;
List<RecruitmentWindow> recruitmentWindows;
List<ExamPattern> examPatterns;
List<AnnualSummary> historicalSummary;
List<Risk> qualificationRisks;
List<ActionItem> actionTimeline;
DataCoverage dataCoverage;
Instant generatedAt;
String algorithmVersion;
```

Use defensive copies and stable ordering by year, route priority, month, and job ID.

- [ ] **Step 4: Implement transparent calculations**

- Age uses exact `Period.between(birthDate, referenceDate).getYears()`.
- A job counts once; an event counts once by event ID.
- Formal count only includes `ESTABLISHMENT` or `PUBLIC_INSTITUTION_FORMAL`.
- Routes map from organization type and job family according to the spec.
- Evidence strength is `STRONG` for at least 20 jobs from at least 5 events, `MODERATE` for at least 5 events, `LIMITED` for 3–4 events, otherwise `INSUFFICIENT`.
- Expected windows use historical months and never produce a deadline without an official future event date.
- Risks include pending credential verification, unknown political affiliation, unverified employment history, and incomplete coverage.

- [ ] **Step 5: Run service tests and verify GREEN**

Run: `mvn -pl career-application -am -Dtest=CareerPlanServiceTest test`

Expected: all planner service tests pass.

- [ ] **Step 6: Add partial-data and deterministic-order regression tests**

Verify incomplete coverage produces a warning instead of zero, duplicate jobs do not inflate counts, duplicate jobs within one event do not inflate event counts, and shuffled input produces identical output ordering.

- [ ] **Step 7: Run the complete application module tests**

Run: `mvn -pl career-application -am test`

Expected: all domain and application tests pass.

- [ ] **Step 8: Commit Task 2**

```text
git add career-application
git commit -m "feat: generate deterministic career plans"
```

---

### Task 3: PostgreSQL Historical Planning Adapter

**Requirements:** `REQ-PLAN-003`, `REQ-PLAN-004`, `REQ-DEGRADE-001`

**Files:**
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/JdbcCareerPlanQueryAdapter.java`
- Test: `career-infrastructure/src/test/java/com/careeros/infrastructure/persistence/JdbcCareerPlanQueryAdapterTest.java`

**Interfaces:**
- Implements `CareerPlanPorts.CareerPlanQuery`.
- Uses `RepositoryPorts.CandidateProfiles` for the candidate aggregate and `JdbcTemplate` for the historical read model.
- Returns active 2024–2026 bachelor/master target technical jobs and `source_year_coverage` rows.

- [ ] **Step 1: Write a failing Testcontainers adapter test**

Insert four fixtures: one valid formal software role, one doctorate teacher, one labor-dispatch role, and one unrelated clinical role. Assert only the valid target role is returned and that a `PARTIAL` source-year row remains visible in coverage.

- [ ] **Step 2: Run adapter test and verify RED**

Run: `mvn -pl career-infrastructure -am -Dtest=JdbcCareerPlanQueryAdapterTest test`

Expected: compilation fails because the adapter does not exist.

- [ ] **Step 3: Implement the read query and row mapping**

The SQL predicate must require years in range, active jobs, bachelor/master minimum education, target major text, and target title/family/duties. It must exclude doctorate-only, teaching, postdoctoral, labor-dispatch, and project-based rows without deleting them from the database. Join recruitment event and organization once, coalesce date/timestamp application fields to dates, and map JSON arrays with Jackson rather than string splitting.

- [ ] **Step 4: Load coverage independently from job rows**

Query `source_year_coverage` joined to `recruitment_source`, retain all configured source-years in range, and preserve `NOT_DISCOVERED`, `ACCESS_FAILED`, `PARTIAL`, `COMPLETE`, and `NO_TARGET_RECORDS` distinctions.

- [ ] **Step 5: Run adapter tests and verify GREEN**

Run: `mvn -pl career-infrastructure -am -Dtest=JdbcCareerPlanQueryAdapterTest test`

Expected: the target row and partial coverage are returned exactly once.

- [ ] **Step 6: Run infrastructure tests**

Run: `mvn -pl career-infrastructure -am test`

Expected: all infrastructure, application, and domain tests pass.

- [ ] **Step 7: Commit Task 3**

```text
git add career-infrastructure
git commit -m "feat: query historical planning evidence"
```

---

### Task 4: Career Plan REST Endpoint and Wiring

**Requirements:** `REQ-PLAN-001`, `REQ-PLAN-002`, `REQ-PLAN-004`, `REQ-DEGRADE-001`

**Files:**
- Create: `career-web/src/main/java/com/careeros/CareerPlanController.java`
- Modify: `career-web/src/main/java/com/careeros/ApplicationConfiguration.java`
- Modify: `career-web/src/main/java/com/careeros/ApiExceptionHandler.java`
- Test: `career-web/src/test/java/com/careeros/CareerPlanApiTest.java`
- Test: `career-web/src/test/java/com/careeros/CareerPlanEndToEndTest.java`

**Interfaces:**
- `GET /api/v1/candidates/{candidateId}/career-plan?targetYear=2027&asOf=2026-08-22`.
- Defaults: target year `asOf.year + 1`, `asOf = LocalDate.now(clock)`.
- Target year range: current year through current year + 5.

- [ ] **Step 1: Write failing standalone controller tests**

Assert successful JSON contains `currentScenario.code`, `recommendedRoutes`, `ageWindows`, `qualificationRisks`, `dataCoverage`, and `algorithmVersion`. Assert an invalid target year returns 400 with a useful detail message.

- [ ] **Step 2: Run controller test and verify RED**

Run: `mvn -pl career-web -am -Dtest=CareerPlanApiTest test`

Expected: compilation fails because the controller is absent.

- [ ] **Step 3: Implement controller and bean wiring**

Use constructor injection. The controller performs request-range validation and delegates all business calculations. `ApplicationConfiguration` wires `CareerPlanService` with the query adapter and existing `Clock`.

- [ ] **Step 4: Run controller test and verify GREEN**

Run: `mvn -pl career-web -am -Dtest=CareerPlanApiTest test`

Expected: standalone controller tests pass.

- [ ] **Step 5: Write and run a failing end-to-end test**

Against Flyway/Testcontainers and the seed candidate, assert birth date `1992-12-31`, current scenario `PRE_GRADUATION`, all three scenario codes, at least one historical annual summary, and at least one representative job URL.

Run: `mvn -pl career-web -am -Dtest=CareerPlanEndToEndTest test`

Expected before final wiring fixes: endpoint or data assertions fail.

- [ ] **Step 6: Complete mappings and verify end-to-end GREEN**

Run: `mvn -pl career-web -am -Dtest=CareerPlanApiTest,CareerPlanEndToEndTest test`

Expected: both tests pass.

- [ ] **Step 7: Commit Task 4**

```text
git add career-web
git commit -m "feat: expose candidate career plans"
```

---

### Task 5: Candidate Profile Controls for Planning Facts

**Requirements:** `REQ-AGE-001`, `REQ-EDU-001`, `REQ-EXP-001`

**Files:**
- Modify: `career-ui/src/features/profile/profileSchema.ts`
- Modify: `career-ui/src/features/profile/ProfileForm.tsx`
- Modify: `career-ui/src/features/profile/ProfilePage.tsx`
- Modify: `career-ui/src/features/profile/ProfilePage.test.tsx`
- Modify: `career-ui/src/features/profile/ProfileGate.test.tsx`
- Modify: `career-ui/src/styles/profile.css`

- [ ] **Step 1: Write failing UI tests**

Assert the form displays full birthday, gender, political affiliation, and an employment evidence section; saving preserves 2027 expected education and sends an empty employment history rather than inventing dates.

- [ ] **Step 2: Run profile tests and verify RED**

Run: `npm test -- ProfilePage.test.tsx ProfileGate.test.tsx`

Working directory: `career-ui`

Expected: fields are not found.

- [ ] **Step 3: Extend TypeScript contracts and form controls**

Add exact union types matching Java enums. Allow adding/removing employment rows with employer, role, start/end, mode, verification status, and comma-separated evidence-type labels. Keep all document content local and out of the request.

- [ ] **Step 4: Make confirmation status explicit**

Add labels for the three new fact keys. Display unknown political affiliation and unverified employment as planning risks without blocking the whole opportunity list.

- [ ] **Step 5: Run profile tests and verify GREEN**

Run: `npm test -- ProfilePage.test.tsx ProfileGate.test.tsx`

Working directory: `career-ui`

Expected: profile tests pass.

- [ ] **Step 6: Run UI typecheck**

Run: `npm run typecheck`

Working directory: `career-ui`

Expected: no TypeScript errors.

- [ ] **Step 7: Commit Task 5**

```text
git add career-ui/src/features/profile career-ui/src/styles/profile.css
git commit -m "feat: capture career planning evidence"
```

---

### Task 6: “我的规划” React Page

**Requirements:** `REQ-PLAN-001`, `REQ-PLAN-002`, `REQ-PLAN-003`, `REQ-PLAN-004`, `REQ-PLAN-005`, `REQ-CALENDAR-001`, `REQ-DEGRADE-001`

**Files:**
- Create: `career-ui/src/features/planning/planningApi.ts`
- Create: `career-ui/src/features/planning/CareerPlanPage.tsx`
- Create: `career-ui/src/features/planning/CareerPlanPage.test.tsx`
- Modify: `career-ui/src/api/http.ts`
- Modify: `career-ui/src/App.tsx`
- Modify: `career-ui/src/app/AppShell.tsx`
- Create: `career-ui/src/styles/planning.css`
- Modify: `career-ui/src/main.tsx`

- [ ] **Step 1: Write failing page tests**

Mock the API with three scenarios, four routes, age windows, annual counts, one risk, actions, and one representative job. Assert the page renders:

```text
我的半体制规划
当前：本科已完成，硕士预计 2027 毕业
事业单位信息技术岗
2028 春季仍在 35 周岁窗口内
决策指数，不是录取概率
```

Assert the representative job links to `/opportunities/{jobId}`, partial coverage says “数据尚未补齐”, and no percentage admission-probability copy appears.

- [ ] **Step 2: Run page test and verify RED**

Run: `npm test -- CareerPlanPage.test.tsx`

Working directory: `career-ui`

Expected: module and route are missing.

- [ ] **Step 3: Implement API contract and query key**

`getCareerPlan(candidateId, targetYear)` calls the approved endpoint. Add `queryKeys.careerPlan(candidateId, targetYear)` and exact TypeScript response types.

- [ ] **Step 4: Implement the page in the approved reading order**

Render conclusion, scenarios, historical support, routes, age windows, timing/exams, risks, actions, and official evidence. Use semantic headings, lists, `<time>`, and accessible links. Keep collection status visible beside statistics.

- [ ] **Step 5: Add routing, navigation, and styling**

Register `/plan` behind `ProfileGate`, add “我的规划” after “今天”, import `planning.css`, and create a responsive two-column desktop/one-column mobile layout without hover-only information.

- [ ] **Step 6: Run page tests and verify GREEN**

Run: `npm test -- CareerPlanPage.test.tsx App.test.tsx`

Working directory: `career-ui`

Expected: planning and route tests pass.

- [ ] **Step 7: Run the complete UI verification**

Run: `npm run verify`

Working directory: `career-ui`

Expected: typecheck and all Vitest tests pass.

- [ ] **Step 8: Commit Task 6**

```text
git add career-ui
git commit -m "feat: add semi-public career planner"
```

---

### Task 7: Product Verification, Documentation, and Private Push

**Requirements:** all planner requirements

**Files:**
- Modify: `README.md`
- Create: `docs/PHASE5A_CAREER_PLANNER.md`

- [ ] **Step 1: Document the user flow and data limits**

Document `/plan`, three scenarios, deterministic age/education/experience rules, historical support wording, and the fact that government-SOE coverage is currently limited.

- [ ] **Step 2: Run full backend and frontend verification**

Run: `mvn test`

Expected: all Maven module tests and the frontend Maven verification pass with zero failures.

- [ ] **Step 3: Package the application**

Run: `mvn -DskipTests package`

Expected: `career-web/target/career-web-0.1.0-SNAPSHOT.jar` is produced.

- [ ] **Step 4: Restart locally and verify health/data**

Restart the existing local process with database URL `jdbc:postgresql://localhost:55432/career_os`. Verify:

```text
GET /actuator/health -> UP
GET /api/v1/candidates/01992f09-0000-7000-8000-000000000001/career-plan?targetYear=2027
  -> currentScenario PRE_GRADUATION
  -> non-empty recommendedRoutes
  -> non-empty actionTimeline
```

- [ ] **Step 5: Browser acceptance test**

Using Playwright, open `http://localhost:8080/plan`, verify the conclusion, all three scenarios, historical counts, at least one official representative role, age window, risk list, and action timeline. Confirm no console errors or failed API requests and capture a screenshot for inspection.

- [ ] **Step 6: Request code review and resolve findings**

Review the complete diff against the approved spec. Fix every Critical and Important issue, then re-run affected tests and the full verification command.

- [ ] **Step 7: Commit docs and final fixes**

```text
git add README.md docs career-domain career-application career-infrastructure career-web career-ui
git commit -m "docs: hand off the career planner"
```

- [ ] **Step 8: Push the private branch**

Run: `git push origin codex/phase4b-career-workbench`

Expected: the remote private repository contains all planner commits and the working tree is clean.
