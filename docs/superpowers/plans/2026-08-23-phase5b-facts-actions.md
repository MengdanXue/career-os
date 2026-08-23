# Phase 5B Facts and Personal Actions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the operator-style home page with a deterministic zero-to-three personal action queue and expose evidence-backed profile repair tasks without inventing candidate facts.

**Architecture:** Add a `personal` application slice that derives candidate evidence tasks from the existing `CandidateProfile` and `CandidateFacts` snapshot, then combines those tasks with current job deadlines and relevant acquisition changes in a deterministic `PersonalActionService`. Keep controllers as request/response adapters, reuse existing repository and decision ports, and let React consume the new read models directly. Do not persist derived tasks or actions in this slice.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL/Flyway, JUnit 5/AssertJ, React 19, TypeScript, TanStack Query, Vitest/Testing Library, Playwright acceptance.

**Spec:** `docs/superpowers/specs/2026-08-23-phase5b-personal-decision-loop-design.md` — delivery slice 1; P5B-ACT-001, P5B-ACT-002, P5B-PROFILE-001, P5B-PROFILE-002, and the slice-1 portions of sections 10.1, 10.5, 11, 12, 13, and 14.

**Delivery status (2026-08-23): COMPLETE for Phase 5B slice 1.** Tasks 1–7 below were implemented and committed incrementally. Final verification: 328 Java tests, 41 UI tests, production UI build, Flyway fresh/upgrade coverage, health/API smoke checks, and desktop/mobile Playwright acceptance. Phase 5B slices 2–4 remain intentionally out of scope.

**Final truth-safety corrections:** legacy `experienceYears` is display-only; hard eligibility and fit use confirmed, verified full-time intervals merged by complete months. Confirmed empty employment is known zero, while unknown or unverified history remains unknown. Decision cache version `decision-v2-verified-employment` invalidates legacy snapshots and uses the official application deadline as the fixed qualification reference date. Internal action/impact ranking is full-set and uses a single batched admission lookup rather than truncating at 100 jobs.

## Global Constraints

- Follow red-green-refactor for every behavioral change; run the named failing test before implementation.
- Do not infer or seed employment records, skills, research evidence, political affiliation, graduation month, credential completion, or degree date.
- `experienceYears` remains a legacy display/migration hint only. Evidence task and action decisions must use verified `CandidateEmploymentRecord` rows and candidate fact states.
- Preserve current candidate save, confirmation fingerprint, row-lock, and server-owned profile-version semantics.
- Same database snapshot and `asOf` must return the same ordered actions. Tie-break with stable enum/code and UUID values, never iteration order.
- Return at most three personal actions. Raw extraction Review Queue counts must not become personal actions or appear on the primary Today page.
- A missing official deadline stays `null`; never manufacture a current deadline from historical dates.
- Derived-subsystem failures are represented as unavailable sections, not misleading zeroes.
- Do not modify migrations V1–V23. Any seed repair is a narrowly guarded V24 migration.
- Complete and verify this slice before planning or implementing Phase 5B slices 2–4.

---

## Task 1: Define deterministic profile evidence tasks

**Files:**

- Create: `career-application/src/main/java/com/careeros/application/personal/CandidateEvidenceTask.java`
- Create: `career-application/src/main/java/com/careeros/application/personal/CandidateEvidenceTaskPorts.java`
- Create: `career-application/src/main/java/com/careeros/application/personal/CandidateEvidenceTaskService.java`
- Create: `career-application/src/test/java/com/careeros/application/personal/CandidateEvidenceTaskServiceTest.java`

- [ ] **Step 1: Write failing tests for real candidate conflicts and impact ordering**

Cover these cases with fixed candidate IDs and fact snapshots:

```java
@Test
void derivesEmploymentPoliticalGraduationCredentialAndSkillTasksWithoutInventingValues() {
    var result = service.tasks(CANDIDATE_ID, LocalDate.of(2026, 8, 23));

    assertThat(result.items()).extracting(CandidateEvidenceTask::code).contains(
        "VERIFY_EMPLOYMENT_HISTORY", "CONFIRM_POLITICAL_AFFILIATION",
        "CONFIRM_MASTER_GRADUATION_MONTH", "VERIFY_MASTER_CREDENTIAL",
        "ADD_SKILL_EVIDENCE", "ADD_RESEARCH_EVIDENCE");
    assertThat(result.items()).allSatisfy(task -> assertThat(task.deepLink()).startsWith("/profile#"));
}

@Test
void neverTreatsLegacyExperienceYearsAsVerifiedEmploymentEvidence() {
    assertThat(service.tasks(candidateWithSevenLegacyYearsAndNoEmployment(), AS_OF).items())
        .extracting(CandidateEvidenceTask::code).contains("VERIFY_EMPLOYMENT_HISTORY");
}

@Test
void ordersByAffectedTargetJobsThenStableCode() { /* equal-impact tasks remain stable */ }
```

The tests must also distinguish an explicit confirmed empty set from an `UNKNOWN` or seed-confirmed empty set, and must assert `available=false` when either profile facts or target impact cannot be loaded.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
mvn.cmd -pl career-application -Dtest=CandidateEvidenceTaskServiceTest test
```

Expected: compilation failure because the `personal` read models and service do not exist.

- [ ] **Step 3: Implement the read model and ports**

Use immutable records and enums:

```java
public record CandidateEvidenceTask(
    String code,
    EvidenceTaskKind kind,
    CandidateFactKey factKey,
    String title,
    String reason,
    int affectedJobCount,
    EvidenceStrength evidenceStrength,
    String deepLink
) {}

public record CandidateEvidenceTasks(
    UUID candidateId,
    LocalDate asOf,
    boolean available,
    String message,
    List<CandidateEvidenceTask> items
) {}
```

Define ports for a full `CandidateProfileFacts` snapshot and qualification-impact counts keyed by `CandidateFactKey`. The impact port must report availability separately from an actual zero count.

- [ ] **Step 4: Implement evidence-task derivation**

Derive, at minimum:

- no verified employment records or unknown `EMPLOYMENT_HISTORY` → `VERIFY_EMPLOYMENT_HISTORY`;
- unknown/unconfirmed political fact → `CONFIRM_POLITICAL_AFFILIATION` while preserving the stored value;
- expected master's education with missing graduation month → `CONFIRM_MASTER_GRADUATION_MONTH`;
- expected overseas master's credential not `VERIFIED` → `VERIFY_MASTER_CREDENTIAL`;
- professional title fact not confirmed → `VERIFY_PROFESSIONAL_TITLE`;
- skills/research unknown or unconfirmed empty seed data → `ADD_SKILL_EVIDENCE` / `ADD_RESEARCH_EVIDENCE`.

Sort by descending affected-job count, then task kind priority, then code. Use stable Chinese titles/reasons from the service. Never classify a user-confirmed empty collection as unknown merely because it is empty.

- [ ] **Step 5: Run the focused test and verify GREEN**

Run the same Maven command and confirm all evidence-task tests pass.

- [ ] **Step 6: Commit**

```powershell
git add career-application/src/main/java/com/careeros/application/personal career-application/src/test/java/com/careeros/application/personal
git commit -m "feat: derive candidate evidence tasks"
```

---

## Task 2: Repair only incorrect seed confirmations

**Files:**

- Create: `career-infrastructure/src/main/resources/db/migration/V24__repair_empty_seed_fact_confirmations.sql`
- Modify: `career-web/src/test/java/com/careeros/CandidateProfileTransactionIntegrationTest.java`
- Test: `career-web/src/test/java/com/careeros/CandidateProfileTransactionIntegrationTest.java`

- [ ] **Step 1: Add a failing integration assertion for the upgraded seed**

After all migrations, assert for candidate `01992f09-0000-7000-8000-000000000001`:

- `EXPERIENCE_YEARS`, `EMPLOYMENT_HISTORY`, and `POLITICAL_AFFILIATION` remain `UNKNOWN`;
- empty `SKILLS` and empty `RESEARCH_KEYWORDS` are not reported `CONFIRMED` when their confirmation came from the known seed profile/fingerprint;
- `PROFESSIONAL_TITLES` and existing confirmed education/location/target facts remain confirmed;
- the candidate's stored profile values are unchanged.

- [ ] **Step 2: Run the integration test and verify RED**

```powershell
mvn.cmd -pl career-web -Dtest=CandidateProfileTransactionIntegrationTest test
```

Expected: the known empty seed facts are still confirmed, or the new migration is absent.

- [ ] **Step 3: Add a narrowly guarded V24 migration**

Update only confirmation rows for the fixed seed candidate when all of these match:

- exact candidate ID;
- exact expected seed/server profile version or a guarded known-version set;
- exact empty value fingerprint produced by `CandidateFacts.fingerprint`;
- current status/source prove it is the historical seed confirmation;
- corresponding JSON profile collection is still `[]`.

Set status to `UNKNOWN`, clear `confirmed_at`, and update `updated_at`. Do not change profile JSON and do not touch any user-edited profile version.

- [ ] **Step 4: Verify fresh migration and upgrade safety**

Run the integration test, then the complete `career-web` test module. Confirm Flyway applies V24 on a fresh test database and a user-edited fixture is not altered.

- [ ] **Step 5: Commit**

```powershell
git add career-infrastructure/src/main/resources/db/migration/V24__repair_empty_seed_fact_confirmations.sql career-web/src/test/java/com/careeros/CandidateProfileTransactionIntegrationTest.java
git commit -m "fix: repair empty seed fact confirmations"
```

---

## Task 3: Generate at most three personal actions

**Files:**

- Create: `career-application/src/main/java/com/careeros/application/personal/PersonalAction.java`
- Create: `career-application/src/main/java/com/careeros/application/personal/PersonalActionPorts.java`
- Create: `career-application/src/main/java/com/careeros/application/personal/PersonalActionService.java`
- Create: `career-application/src/test/java/com/careeros/application/personal/PersonalActionServiceTest.java`
- Modify: `career-application/src/main/java/com/careeros/application/workbench/WorkbenchPorts.java`

- [ ] **Step 1: Write failing ordering, cap, and determinism tests**

Use a fixed `Clock` and shuffled input lists to prove:

```java
assertThat(actions).hasSize(3);
assertThat(actions).extracting(PersonalAction::kind).containsExactly(
    ActionKind.CURRENT_JOB_DEADLINE,
    ActionKind.CANDIDATE_EVIDENCE,
    ActionKind.TARGET_JOB_CHANGE);
assertThat(service.actions(CANDIDATE_ID, AS_OF)).isEqualTo(service.actions(CANDIDATE_ID, AS_OF));
```

Also assert:

- 15-day deadlines are excluded; 14-day deadlines are included;
- `INELIGIBLE` current jobs never create deadline actions;
- missing deadline stays `null`;
- Review Queue count cannot appear in an action;
- unavailable optional inputs produce one availability warning section, not a fake action count;
- each action carries title, reason, affected count, due date, evidence strength, and one internal deep link.

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
mvn.cmd -pl career-application -Dtest=PersonalActionServiceTest test
```

- [ ] **Step 3: Implement deterministic action generation**

Define:

```java
public record PersonalAction(
    String id,
    ActionKind kind,
    int priority,
    String title,
    String reason,
    int affectedObjectCount,
    LocalDate dueOn,
    EvidenceStrength evidenceStrength,
    String deepLink
) {}
```

Generate actions in the spec priority order. In this slice, fully support current official deadlines, candidate evidence gaps, and relevant acquisition changes. Keep explicit empty adapters for application-next-step and preparation-timeline inputs so slices 3 and 4 can extend the service without changing the response contract.

Only current decision signals with deadline `asOf..asOf+14`, tier not `EXCLUDED`, and eligibility not `INELIGIBLE` are deadline candidates. Collapse evidence gaps only when they share the same target field and deep link. Collapse acquisition rows into a single relevant-change action only when the supplied change port reports target-job impact; never use raw batch/review counts as relevance.

Sort by numeric priority, due date with nulls last, descending affected count, kind, and stable ID; then `limit(3)`.

- [ ] **Step 4: Run the focused test and the workbench regression tests**

```powershell
mvn.cmd -pl career-application -Dtest=PersonalActionServiceTest,WorkbenchSummaryServiceTest test
```

- [ ] **Step 5: Commit**

```powershell
git add career-application/src/main/java/com/careeros/application/personal career-application/src/test/java/com/careeros/application/personal career-application/src/main/java/com/careeros/application/workbench/WorkbenchPorts.java
git commit -m "feat: prioritize personal daily actions"
```

---

## Task 4: Wire services and expose candidate APIs

**Files:**

- Create: `career-web/src/main/java/com/careeros/PersonalActionController.java`
- Modify: `career-web/src/main/java/com/careeros/ApplicationConfiguration.java`
- Create: `career-web/src/test/java/com/careeros/PersonalActionApiTest.java`
- Modify: `career-web/src/main/java/com/careeros/CareerMvpController.java`
- Modify: `career-web/src/test/java/com/careeros/CandidatePlanningProfileApiTest.java`

- [ ] **Step 1: Write failing standalone API tests**

Assert:

```http
GET /api/v1/candidates/{candidateId}/personal-actions?asOf=2026-08-23
GET /api/v1/candidates/{candidateId}/evidence-tasks?asOf=2026-08-23
```

The JSON must include candidate ID, `asOf`, `generatedAt` or deterministic snapshot metadata, `available`, `message`, and item arrays. Invalid dates return RFC 9457-style problem details through the existing exception handler. Unknown candidates return 404.

- [ ] **Step 2: Run focused API tests and verify RED**

```powershell
mvn.cmd -pl career-web -Dtest=PersonalActionApiTest,CandidatePlanningProfileApiTest test
```

- [ ] **Step 3: Wire existing repositories and decision sources through application ports**

In `ApplicationConfiguration`:

- adapt `CandidateProfileService.facts(candidateId)` to the facts snapshot port;
- adapt `DecisionRankingService` results to current deadline and qualification-impact signals;
- adapt acquisition changes only after mapping them to a target-job impact signal; if relevance cannot be proved, return an available empty set rather than surfacing raw ingestion changes;
- construct `CandidateEvidenceTaskService` and `PersonalActionService` as Spring beans with the shared `Clock`.

Do not add repository calls to the controller.

- [ ] **Step 4: Add controller endpoints and validation**

Default missing `asOf` to `LocalDate.now(clock)` inside the service boundary. Echo the resolved date. Keep Today's old `/workbench-summary` endpoint for data-management compatibility, but stop using it as the personal homepage API.

- [ ] **Step 5: Verify focused and complete web tests**

```powershell
mvn.cmd -pl career-web test
```

- [ ] **Step 6: Commit**

```powershell
git add career-web/src/main/java/com/careeros career-web/src/test/java/com/careeros
git commit -m "feat: expose personal actions and evidence tasks"
```

---

## Task 5: Rebuild Today around personal actions

**Files:**

- Modify: `career-ui/src/features/today/todayApi.ts`
- Modify: `career-ui/src/features/today/TodayPage.tsx`
- Modify: `career-ui/src/features/today/TodayPage.test.tsx`
- Modify: `career-ui/src/styles/today.css`
- Modify: `career-ui/src/api/http.ts`

- [ ] **Step 1: Replace Today tests with the personal contract and verify RED**

Test these states:

- three returned actions render exactly three first-screen cards in backend order;
- a candidate evidence action links to `/profile#employment-history`;
- an official deadline renders its exact date and remaining days without client recomputation changing the order;
- empty items render `今天没有新的资格、截止或准备事项`;
- partial availability renders one merged warning;
- source health and Review Queue are only in a collapsed/secondary data-management footer, and Review Queue count is absent from the primary action area.

- [ ] **Step 2: Run the focused Vitest file and verify RED**

```powershell
npm.cmd --prefix career-ui test -- TodayPage.test.tsx --run
```

- [ ] **Step 3: Update the API types and query key**

Add exact discriminated unions for `ActionKind` and `EvidenceStrength`; fetch `/personal-actions` with a stable local `asOf` date. Do not merge workbench data into actions in React.

- [ ] **Step 4: Implement the personal Today layout**

The first screen is:

```text
今天最重要的事
  [action 1]
  [action 2]
  [action 3]

你的机会概览 (secondary)
数据管理 (secondary/collapsed)
```

Use accessible headings, one clear call-to-action link per action, official due-date labels, evidence-strength labels, and calm empty-state copy. Remove the four operator attention categories and their Review Queue action card.

- [ ] **Step 5: Run focused and complete UI tests**

```powershell
npm.cmd --prefix career-ui test -- TodayPage.test.tsx --run
npm.cmd --prefix career-ui test -- --run
```

- [ ] **Step 6: Commit**

```powershell
git add career-ui/src/features/today career-ui/src/styles/today.css career-ui/src/api/http.ts
git commit -m "feat: make today a personal action queue"
```

---

## Task 6: Put the highest-impact evidence gaps at the top of My Profile

**Files:**

- Modify: `career-ui/src/features/profile/profileApi.ts`
- Modify: `career-ui/src/features/profile/profileSchema.ts`
- Modify: `career-ui/src/features/profile/ProfilePage.tsx`
- Modify: `career-ui/src/features/profile/ProfileForm.tsx`
- Modify: `career-ui/src/features/profile/ProfilePage.test.tsx`
- Modify: `career-ui/src/styles/profile.css`

- [ ] **Step 1: Write failing profile behavior tests**

Assert that:

- evidence tasks load alongside facts and the highest-impact task appears before the generic confirmation counts;
- clicking a task enters edit mode, navigates/focuses the matching anchored field group, and preserves the backend reason/impact count;
- `experienceYears=7` plus zero verified employment records displays `旧资料记录 7 年；硬资格仍需逐段核验`, never `已核验 7 年`;
- an `UNKNOWN` political fact remains visibly unknown even when a legacy stored enum value exists;
- empty skills/research with unknown status render `尚未提供`, not `明确没有`;
- an unavailable evidence-task API does not block editing facts and shows a localized warning.

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
npm.cmd --prefix career-ui test -- ProfilePage.test.tsx --run
```

- [ ] **Step 3: Add evidence-task types and query**

Reuse the backend task enum/string contract from `todayApi.ts` through a shared `personalTypes.ts` module if duplication appears. Invalidate `candidate-evidence-tasks`, `personal-actions`, `decisions`, and `career-plan` after a successful save/confirmation.

- [ ] **Step 4: Add stable form anchors and truthful value/status presentation**

Required anchors include:

- `employment-history`
- `political-affiliation`
- `master-graduation`
- `credential-verification`
- `professional-title`
- `skill-evidence`
- `research-evidence`

Display the backend fact status beside each relevant group. Keep the current one-action save/confirm behavior for this slice; `DecisionChangeSummary` is implemented in the later consistency slice, so the post-save UI says the facts were saved and dependent queries are refreshing without fabricating a numerical diff.

- [ ] **Step 5: Run focused and complete UI tests**

```powershell
npm.cmd --prefix career-ui test -- ProfilePage.test.tsx --run
npm.cmd --prefix career-ui test -- --run
```

- [ ] **Step 6: Commit**

```powershell
git add career-ui/src/features/profile career-ui/src/styles/profile.css
git commit -m "feat: guide profile evidence repair"
```

---

## Task 7: Slice verification, browser acceptance, and documentation

**Files:**

- Create: `scripts/phase5b_facts_actions_browser_acceptance.py`
- Modify: `docs/product-requirements.md`
- Modify: `README.md`
- Modify: `docs/superpowers/plans/2026-08-23-phase5b-facts-actions.md`

- [ ] **Step 1: Write browser acceptance before the final UI adjustment**

The script must start from `/`, select the real candidate, and verify:

- first screen has no more than three personal action cards;
- no `535 条数据等待你复核`-style operator task appears;
- employment evidence action deep-links to the employment section on `/profile`;
- Profile truthfully distinguishes legacy experience from verified records;
- empty/unavailable states are readable at desktop and 390px mobile widths;
- browser console has no errors.

- [ ] **Step 2: Run complete backend and frontend verification**

```powershell
mvn.cmd test
npm.cmd --prefix career-ui test -- --run
npm.cmd --prefix career-ui run build
```

Expected: all existing and new tests pass, and the production UI build succeeds.

- [ ] **Step 3: Restart the actual application and run the browser acceptance**

```powershell
pwsh -NoProfile -File scripts/stop-career-os.ps1 -KeepDatabaseRunning
pwsh -NoProfile -File scripts/start-career-os.ps1 -NoBrowser -Rebuild
python scripts/phase5b_facts_actions_browser_acceptance.py
```

Verify `http://localhost:8080/actuator/health` is `UP` and inspect the generated desktop/mobile screenshots.

- [ ] **Step 4: Update product documentation and check off the plan**

Document the new Today/evidence APIs, deterministic limits, known absence of `DecisionChangeSummary` until the later slice, and the non-use of legacy total experience for hard qualification.

- [ ] **Step 5: Perform a spec-coverage and placeholder self-review**

Check every slice-1 requirement against implementation/tests, then scan changed files for `TODO`, placeholder results, hard-coded action counts, client-side priority logic, and accidental Review Queue exposure. Confirm Java/TypeScript enum names match exactly.

- [ ] **Step 6: Request code review and apply only verified fixes**

Use the `requesting-code-review` skill against the complete slice. For any defect, reproduce it with a failing test before changing implementation.

- [ ] **Step 7: Commit and push the verified slice**

```powershell
git add README.md docs/product-requirements.md docs/superpowers/plans/2026-08-23-phase5b-facts-actions.md scripts/phase5b_facts_actions_browser_acceptance.py
git commit -m "docs: verify phase 5b facts and actions"
git push origin codex/phase4b-career-workbench
```

Record the final test counts, browser evidence paths, health result, commit SHA, and private remote branch in the handoff. Do not claim the whole Phase 5B is complete; only slice 1 is complete until slices 2–4 pass their own acceptance.
