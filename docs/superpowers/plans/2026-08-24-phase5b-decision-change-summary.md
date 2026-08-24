# Phase 5B Decision Change Summary Implementation Plan

> **Execution note:** The user approved the design and explicitly requested continuous execution without repeated confirmations. Execute this plan in the current task, one TDD slice at a time.

**Goal:** Make a confirmed profile update immediately explain how qualification decisions changed for the same job snapshot, while keeping all date-sensitive hard conclusions truth-safe.

**Architecture:** Keep `DecisionChangeSummary` as a derived, read-only application model. Capture the previous profile version in the profile UI, recompute the current profile against exactly the job IDs represented by the previous version, and compare status/rule results at one fixed `asOf`. Reuse persisted decision snapshots for the old side; never synthesize an old result. Treat recomputation failure as a derived-data failure only—the saved and confirmed profile remains authoritative.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL/JPA, Flyway, React, TypeScript, TanStack Query, JUnit 5, Vitest, Testing Library.

---

## Task 1: Make qualification dates and decision cache identity truth-safe

**Files:**
- Modify: `career-domain/src/main/java/com/careeros/domain/EligibilityEvaluator.java`
- Modify: `career-domain/src/main/java/com/careeros/domain/FitEvaluator.java`
- Modify: `career-application/src/main/java/com/careeros/application/DecisionIntelligenceService.java`
- Test: `career-domain/src/test/java/com/careeros/domain/EligibilityEvaluatorTest.java`
- Test: `career-domain/src/test/java/com/careeros/domain/FitEvaluatorTest.java`
- Test: `career-application/src/test/java/com/careeros/application/DecisionIntelligenceServiceTest.java`

1. Add failing tests proving that a required experience rule remains `UNCERTAIN` when the official application cutoff is absent, even when publication, age-reference, or request dates exist.
2. Add failing tests proving assessment timestamps remain the real evaluation instant while experience uses a separate qualification cutoff.
3. Add a failing service test proving two otherwise identical events with different application deadlines produce different decision input identities.
4. Run the focused domain and application tests and verify the new tests fail for the intended reasons.
5. Add evaluator overloads that accept nullable `LocalDate qualificationAsOf` separately from `Instant assessedAt`; retain existing overloads for backward compatibility.
6. Remove deadline fallback logic from `DecisionIntelligenceService`. Use only the official event application end date for experience qualification.
7. Include the effective qualification cutoff (or explicit `unknown`) in the evaluator/input identity so a deadline correction cannot reuse a stale snapshot.
8. Run focused tests until green and commit the slice.

## Task 2: Detect political-affiliation requirements per clause

**Files:**
- Create: `career-application/src/main/java/com/careeros/application/personal/PoliticalRequirementClassifier.java`
- Modify: `career-web/src/main/java/com/careeros/ApplicationConfiguration.java`
- Test: `career-application/src/test/java/com/careeros/application/personal/PoliticalRequirementClassifierTest.java`

1. Add failing examples for hard requirements, preference-only wording, unrestricted wording, alternatives such as “党员或民主党派”, and mixed text such as “限中共党员；年龄不限”.
2. Run the focused test and verify the mixed-text case fails under the current global exclusion behavior.
3. Implement a small deterministic classifier that evaluates each source field and punctuation-delimited clause independently and recognizes only explicit hard-requirement patterns.
4. Replace the web configuration helper with the classifier and wire it as a bean/dependency.
5. Run focused application and web tests until green and commit the slice.

## Task 3: Add the versioned decision diff model and service

**Files:**
- Modify: `career-application/src/main/java/com/careeros/application/DecisionPorts.java`
- Create: `career-application/src/main/java/com/careeros/application/personal/DecisionChangeSummary.java`
- Create: `career-application/src/main/java/com/careeros/application/personal/CandidateDecisionDiffService.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/DecisionAssessmentJpaRepository.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/JpaDecisionStore.java`
- Test: `career-application/src/test/java/com/careeros/application/personal/CandidateDecisionDiffServiceTest.java`
- Test: `career-infrastructure/src/test/java/com/careeros/infrastructure/persistence/JpaDecisionStoreIntegrationTest.java`

1. Add failing service tests for: newly eligible, reduced uncertain, newly ineligible, affected-job reasons, fixed job IDs, and unavailable old snapshots.
2. Add a failing persistence test for loading all snapshots for one candidate/profile version without silently selecting another version.
3. Run the focused tests and verify the missing API/behavior failures.
4. Extend `DecisionSnapshots` with an exact profile-version query and implement it in JPA.
5. Implement immutable summary/item models. Use nullable counts plus `available=false` when no old snapshot exists so zero is never invented.
6. Implement recomputation against only the old snapshot’s job IDs at one fixed `asOf`, compare eligibility category and individual rule results, and generate deterministic Chinese reasons.
7. Ensure any recomputation exception is surfaced without changing profile facts or claiming old results are current.
8. Run focused tests until green and commit the slice.

## Task 4: Expose recomputation and summary APIs

**Files:**
- Create: `career-web/src/main/java/com/careeros/DecisionChangeController.java`
- Create: `career-web/src/main/java/com/careeros/DecisionChangeApiModels.java`
- Modify: `career-web/src/main/java/com/careeros/ApplicationConfiguration.java`
- Modify: `career-web/src/main/java/com/careeros/ApiExceptionHandler.java`
- Test: `career-web/src/test/java/com/careeros/DecisionChangeApiTest.java`

1. Add failing MVC tests for `POST /api/v1/candidates/{candidateId}/decision-change-summaries/{previousProfileVersion}` with an explicit `asOf`, successful summary serialization, unavailable-history semantics, and recomputation failure.
2. Run the focused web test and verify it fails because the endpoint is absent.
3. Wire `CandidateDecisionDiffService` and add the endpoint. The path version denotes the previous version; the server reads the current confirmed profile version.
4. Return a stable response shape containing version pair, fixed `asOf`, availability/message, nullable counts, and affected jobs with deep links.
5. Map expected unavailable/failure conditions to clear problem responses without rolling back already completed profile confirmation.
6. Run focused web tests until green and commit the slice.

## Task 5: Show recomputation state and changes on the profile page

**Files:**
- Modify: `career-ui/src/features/profile/profileApi.ts`
- Create: `career-ui/src/features/profile/decisionChangeTypes.ts`
- Modify: `career-ui/src/features/profile/ProfilePage.tsx`
- Modify: `career-ui/src/styles/profile.css`
- Test: `career-ui/src/features/profile/ProfilePage.test.tsx`

1. Add failing UI tests proving the page preserves the newly saved/confirmed facts, shows “决策正在更新”, renders the three change counts and affected jobs after success, and provides an independent retry after recomputation failure.
2. Run the focused Vitest file and verify the expected failures.
3. Capture the pre-save profile version, complete save and fact confirmation first, then call the decision-change endpoint with a stable local-date `asOf`.
4. Add a compact summary panel with “新增可报 / 减少待确认 / 新增不可报”, reason text, and links to local opportunity detail pages.
5. On diff failure, keep the confirmed profile page visible, label old decisions as not current, and show a retry button that reruns only the derived computation.
6. Invalidate decision, workbench, action, plan, and evidence-task queries only after the recomputation attempt is settled.
7. Run the focused UI tests and production build until green and commit the slice.

## Task 6: Regression, browser acceptance, documentation, and private push

**Files:**
- Modify: `docs/superpowers/specs/2026-08-23-phase5b-personal-decision-loop-design.md`
- Modify: `docs/PHASE5A_CAREER_PLANNER.md` or create the next-phase API note if that is clearer after implementation.
- Modify: `README.md`

1. Run the complete Maven test suite with the repository’s Java 21 runtime.
2. Run the complete UI Vitest suite using the bundled Node runtime and run the production build.
3. Restart the local system through the existing scripts and verify in a real browser: edit profile, save/confirm, recomputation progress, successful/unavailable summary, failure/retry, and opportunity deep link.
4. Inspect logs for server/client errors and capture the tested URLs and counts.
5. Update product/API documentation with exact truth-boundary and failure semantics.
6. Run `git diff --check`, inspect the final diff, request a final code review, address material findings, rerun proportional verification, commit, and push the current `codex/` branch to the existing private remote.

