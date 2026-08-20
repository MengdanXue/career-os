# Phase 4A Decision Intelligence Implementation Plan

> **For Codex:** Execute each task in order with strict red-green-refactor. Do not add production behavior before its failing test has been observed.

**Goal:** Deliver an auditable Hangzhou/Zhejiang semi-public-sector decision Agent that gates on deterministic eligibility, computes evidence-aware fit/stability/tier assessments, persists versioned snapshots, ranks active jobs, and returns safe deterministic explanations with an optional model phrasing boundary.

**Architecture:** Pure records and evaluators stay in `career-domain`; orchestration and repository/query interfaces stay in `career-application`; Flyway/JPA adapters stay in `career-infrastructure`; REST and optional model adapters stay in `career-web`. Assessment identity is the tuple `(candidate, job, profileVersion, jobFingerprint, evaluatorVersion)`, so unchanged inputs reuse a snapshot and changed inputs preserve history.

**Tech stack:** Java 21, Maven, Spring Boot 3.5, Spring Data JPA, PostgreSQL/Flyway, Spring AI 1.1, JUnit 5, AssertJ, MockMvc, Testcontainers, ArchUnit.

---

## Task 1: Add decision-domain vocabulary and candidate inputs

**Files:**

- Modify: `career-domain/src/main/java/com/careeros/domain/DomainEnums.java`
- Modify: `career-domain/src/main/java/com/careeros/domain/CandidateProfile.java`
- Create: `career-domain/src/main/java/com/careeros/domain/AssessmentDimension.java`
- Create: `career-domain/src/main/java/com/careeros/domain/FitAssessment.java`
- Create: `career-domain/src/main/java/com/careeros/domain/StabilityAssessment.java`
- Create: `career-domain/src/main/java/com/careeros/domain/DecisionAssessment.java`
- Create: `career-domain/src/test/java/com/careeros/domain/DecisionAssessmentModelTest.java`
- Update candidate constructors in existing test fixtures and production request/mapping code only after the new test fails.

**Steps:**

1. Write model tests proving score range validation, achieved/max validation, immutable collections, coverage calculation, and required version/fingerprint fields.
2. Run `./mvnw -pl career-domain -Dtest=DecisionAssessmentModelTest test` and confirm compilation/test failure is caused by missing decision types.
3. Add the smallest enums and records required to pass: `AssessmentFactStatus`, `AssessmentDimensionType`, `OpportunityTier`, and `RecommendationStatus`.
4. Extend `CandidateProfile` with skills, research keywords, target job families, and preferred organization types; update call sites mechanically.
5. Re-run the focused test, then the complete `career-domain` tests.
6. Commit: `feat(domain): add auditable decision assessment model`.

## Task 2: Implement deterministic fit and stability evaluators

**Files:**

- Create: `career-domain/src/main/java/com/careeros/domain/FitEvaluator.java`
- Create: `career-domain/src/main/java/com/careeros/domain/StabilityEvaluator.java`
- Create: `career-domain/src/main/java/com/careeros/domain/OrganizationStabilityFact.java`
- Create: `career-domain/src/test/java/com/careeros/domain/FitEvaluatorTest.java`
- Create: `career-domain/src/test/java/com/careeros/domain/StabilityEvaluatorTest.java`

**Steps:**

1. Write literal-fixture fit tests for major 25, skill 20, experience 20, research 10, title 10, preference 15, and unknown dimensions.
2. Run the focused fit test and confirm RED due to the missing evaluator.
3. Implement normalized deterministic token matching and numeric/preference rules without fuzzy model calls.
4. Run the focused fit test and confirm GREEN.
5. Write stability tests proving explicit establishment can earn T1 inputs, dispatch/project roles stay high risk, and unknown organization facts do not create points.
6. Run the stability test and confirm RED.
7. Implement evidence-aware stability dimensions and score/coverage calculation.
8. Run both evaluator tests and all domain tests.
9. Commit: `feat(domain): evaluate fit and evidence-backed stability`.

## Task 3: Orchestrate idempotent decisions and ranking

**Files:**

- Modify: `career-application/src/main/java/com/careeros/application/RepositoryPorts.java`
- Create: `career-application/src/main/java/com/careeros/application/DecisionPorts.java`
- Create: `career-application/src/main/java/com/careeros/application/DecisionIntelligenceService.java`
- Create: `career-application/src/main/java/com/careeros/application/DecisionRankingService.java`
- Create: `career-application/src/main/java/com/careeros/application/DecisionExplanationService.java`
- Create: `career-application/src/main/java/com/careeros/application/DecisionExceptions.java`
- Create: `career-application/src/test/java/com/careeros/application/DecisionIntelligenceServiceTest.java`
- Create: `career-application/src/test/java/com/careeros/application/DecisionRankingServiceTest.java`
- Create: `career-application/src/test/java/com/careeros/application/DecisionExplanationServiceTest.java`

**Steps:**

1. Write an in-memory-port test proving hard-ineligible jobs become `EXCLUDED` and are not recommendable.
2. Confirm RED, then add lookup/snapshot/query ports and minimal orchestration.
3. Write and observe failing tests for identical-input reuse and profile/fingerprint version changes creating new snapshots.
4. Implement deterministic snapshot identity and reuse.
5. Write and observe failing ranking tests for active-only filtering, tier-first stable ordering, deadline tie-breaks, and bounded pagination.
6. Implement ranking without loading framework or JPA classes into the application layer.
7. Write and observe failing explanation tests for evidence/unknown warnings and Chinese deterministic text.
8. Implement explanation projection; do not expose a way for model text to modify structured values.
9. Run all application tests and commit: `feat(application): orchestrate decision intelligence`.

## Task 4: Migrate candidate inputs and assessment snapshots

**Files:**

- Create: `career-infrastructure/src/main/resources/db/migration/V7__decision_intelligence.sql`
- Modify: `career-infrastructure/src/test/java/com/careeros/infrastructure/MigrationIntegrationTest.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/JpaModels.java`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/DecisionJpaModels.java`
- Create: decision Spring Data repository interfaces under `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/PersistenceAdaptersConfiguration.java`
- Create: `career-infrastructure/src/test/java/com/careeros/infrastructure/persistence/DecisionPersistenceIntegrationTest.java`

**Steps:**

1. Extend the migration integration test with table, check-constraint, unique-input-key, and FK-index assertions; run it and observe RED.
2. Add candidate JSONB attributes and normalized snapshot/dimension/evidence tables using UUID, TEXT+CHECK, TIMESTAMPTZ, explicit FKs, and query indexes.
3. Re-run migration test to GREEN.
4. Add persistence integration tests proving snapshot round-trip, input-key lookup, active job projections, and evidence links; observe RED.
5. Implement JPA entities, repositories, mappers, and port adapters.
6. Re-run persistence tests and all infrastructure tests.
7. Commit: `feat(infrastructure): persist versioned decision snapshots`.

## Task 5: Replace the legacy basic match score path

**Files:**

- Modify: `career-application/src/main/java/com/careeros/application/CareerDecisionService.java`
- Modify: `career-web/src/main/java/com/careeros/ApplicationConfiguration.java`
- Modify: `career-web/src/main/java/com/careeros/CareerMvpController.java`
- Modify: relevant existing tests that construct `CandidateProfile`.

**Steps:**

1. Add a regression test showing eligibility is a gate and no longer contributes base points to fit.
2. Observe RED against the existing `basicMatchScore` behavior.
3. Route assessment calls to `DecisionIntelligenceService`; keep old opportunity status workflow compatible but stop using `Opportunity.matchScore` as the authoritative ranking.
4. Remove or isolate the obsolete score method after all consumers move.
5. Run domain/application/web regression tests.
6. Commit: `refactor: replace basic match score with decision assessments`.

## Task 6: Add resource-oriented decision APIs

**Files:**

- Create: `career-web/src/main/java/com/careeros/DecisionApiModels.java`
- Create: `career-web/src/main/java/com/careeros/DecisionController.java`
- Modify: `career-web/src/main/java/com/careeros/ApiExceptionHandler.java`
- Create: `career-web/src/test/java/com/careeros/DecisionApiTest.java`
- Modify: `career-web/src/test/java/com/careeros/ArchitectureTest.java` if new boundary rules are needed.

**Steps:**

1. Write MockMvc tests for create/reuse assessment, current decision, paginated ranking, 404, malformed filters, and insufficient-evidence 200 responses.
2. Run the focused API test and confirm RED because routes are absent.
3. Implement candidate-nested `/api/v1/candidates/{candidateId}/job-decisions` resources and DTO mapping.
4. Add RFC 9457 problem codes for missing candidate/job and invalid pagination.
5. Verify structured fields include scores, coverage, tier, evidence, warnings, versions, and the non-probability disclaimer.
6. Run API and architecture tests.
7. Commit: `feat(web): expose decision assessment and ranking APIs`.

## Task 7: Add bounded Agent query with deterministic fallback

**Files:**

- Create: `career-application/src/main/java/com/careeros/application/AgentQueryService.java`
- Create: `career-web/src/main/java/com/careeros/DecisionAgentConfiguration.java`
- Create: `career-web/src/main/java/com/careeros/DecisionAgentController.java`
- Create: `career-web/src/test/java/com/careeros/DecisionAgentApiTest.java`
- Create: `career-web/src/test/java/com/careeros/DecisionAgentModelBoundaryTest.java`

**Steps:**

1. Write a no-model API test proving a Chinese query returns deterministic ranked jobs and explanation; observe RED.
2. Implement bounded intent parsing for location, tier, and job-family terms plus deterministic explanation fallback.
3. Write a model-boundary test with a fake phrasing adapter that returns contradictory text; prove the structured assessment remains authoritative and output is marked model-phrased.
4. Introduce an optional phrasing port and Spring AI adapter only at the web edge; never send a mutation-capable tool.
5. Write a failure test proving model exceptions fall back to deterministic text and expose `fallbackUsed=true`.
6. Run Agent API tests and all web tests.
7. Commit: `feat(agent): answer bounded auditable career queries`.

## Task 8: Golden data, documentation, and full verification

**Files:**

- Create: `career-domain/src/test/resources/golden/decision-jobs.json`
- Create: `career-domain/src/test/java/com/careeros/domain/DecisionGoldenDatasetTest.java`
- Create: `docs/PHASE4A_API.md`
- Modify: `README.md`
- Modify: `docs/current-gap-analysis.md`

**Steps:**

1. Add reviewed fixtures for Hangzhou/Zhejiang T1, T2, T3, excluded, and insufficient-evidence cases, with hand-derived expected outputs.
2. Run the golden test and confirm RED before wiring fixture evaluation.
3. Implement only the fixture loader/projection needed for GREEN.
4. Document APIs, score semantics, Agent boundaries, local run commands, and known limitations.
5. Run `./mvnw test` and record total tests/failures/skips.
6. Run `./mvnw -DskipTests package` and confirm exit code 0.
7. Run `git diff --check` and inspect the full diff against this plan and the approved design.
8. Because sub-agent delegation is not authorized in this task, perform a structured self-review for correctness, security boundaries, architecture dependencies, migrations, and test mutations; fix every critical/important issue and repeat verification.
9. Commit: `docs: complete phase 4a decision intelligence`.
10. Push `codex/phase4a-decision-intelligence` to the existing private remote and verify its remote tracking state.

