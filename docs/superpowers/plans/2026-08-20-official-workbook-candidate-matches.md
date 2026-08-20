# Official Workbook Candidate Matches Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn downloaded Zhejiang/Hangzhou official recruitment workbooks into repeatable, profile-matched job results visible in Career OS without weakening evidence gates.

**Architecture:** Keep the existing acquisition and stable-key pipeline. Extend deterministic workbook handling, scope every attachment to its own snapshot event, and classify each imported row into a review-only candidate-match read model. Workbook parsing alone never creates a trusted admission; only a later evidence-backed human decision can promote an identity-confirmed row into the decision-ready opportunity ledger.

**Tech Stack:** Java 21, Spring Boot 3, Apache POI, PostgreSQL/JPA, React, TypeScript, TanStack Query, JUnit 5, Vitest.

**Spec:** `docs/CAREER_OS_MASTER_SPEC.md`

## Global Constraints

- Preserve every source row and official URL; exclusion is a status, never physical deletion.
- Deterministic rules decide hard eligibility and target-scope exclusions; no LLM inference for age, degree, deadline, or employment identity.
- `employment_type` stays `UNKNOWN` until explicit official evidence exists.
- Stable job keys and content fingerprints remain the basis for inserted/updated/unchanged/deactivated deltas.
- A repeated acquisition of the same workbook must not create duplicate jobs or changes.

---

### Task 1: Preserve announcement context and classify workbook attachments

**Files:**
- Modify: `career-application/src/main/java/com/careeros/application/AcquisitionService.java`
- Modify: `career-application/src/main/java/com/careeros/application/AcquiredDocumentProcessor.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/Phase2DocumentProcessor.java`
- Test: `career-application/src/test/java/com/careeros/application/AcquisitionServiceTest.java`
- Test: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/Phase2DocumentProcessorTest.java`

**Interfaces:**
- Consumes: discovered announcement title, attachment URI `fileName`, stored workbook bytes.
- Produces: `ProcessingResult.ignored(String)` and an `ImportCommand` with the parent announcement title and derived default organization name.

- [x] **Step 1: Write failing acquisition and processor tests**

Add assertions that an attachment receives its parent announcement title, and that `报名表` workbooks return a successful ignored result without invoking the importer.

- [x] **Step 2: Run tests and confirm the expected failures**

Run `mvn -pl career-application,career-infrastructure -am -Dtest=AcquisitionServiceTest,Phase2DocumentProcessorTest -Dsurefire.failIfNoSpecifiedTests=false test` and confirm the title assertion and ignored-status assertion fail.

- [x] **Step 3: Implement minimal context propagation and ignored attachment handling**

Pass the parent title through acquisition processing, add `IGNORED` as a non-failing processing status, decode the `fileName` query parameter, and ignore `报名表`, `应聘表`, and `申请表` workbooks.

- [x] **Step 4: Run the focused tests until green**

Run the same Maven command and require zero failures.

### Task 2: Parse real official workbook variants

**Files:**
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/OfficialExcelImportService.java`
- Test: `career-infrastructure/src/test/java/com/careeros/infrastructure/persistence/OfficialExcelImportServiceTest.java`
- Test fixtures: `career-infrastructure/src/test/resources/fixtures/official-workbooks/`

**Interfaces:**
- Consumes: `ImportCommand` with announcement title, official article URL, region, and default organization.
- Produces: normalized jobs with organization, title, headcount, age, education, major, experience, professional-title text, duties, job family, stable key, and fingerprint.

- [x] **Step 1: Write failing tests for single-organization and alternate-header sheets**

Cover a workbook whose header is `岗位名称/岗位职责/人数/学历/专业要求` with no organization column, plus `计划数`, `用人学院（部门）`, `其他条件`, and `职称要求` aliases.

- [x] **Step 2: Run the focused importer test and confirm the rows are rejected or incomplete**

Run `mvn -pl career-infrastructure -am -Dtest=OfficialExcelImportServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` and confirm failure is caused by the missing behavior.

- [x] **Step 3: Implement the minimal parser extensions**

Derive a default organization from the parent announcement title, combine qualification text into duties/rule extraction, add the required aliases, parse professional titles, and classify computing/data/AI/information-system families using title, duties, and major text.

- [x] **Step 4: Run importer and existing infrastructure tests until green**

Run the focused test, then `mvn -pl career-infrastructure -am test`.

### Task 3: Automatically classify official jobs and expose candidate matches

**Files:**
- Create: `career-application/src/main/java/com/careeros/application/OfficialJobAdmissionService.java`
- Create: `career-application/src/main/java/com/careeros/application/CandidateMatchService.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/OfficialExcelImportService.java`
- Create: `career-web/src/main/java/com/careeros/CandidateMatchController.java`
- Modify: `career-web/src/main/java/com/careeros/ApplicationConfiguration.java`
- Test: `career-application/src/test/java/com/careeros/application/OfficialJobAdmissionServiceTest.java`
- Test: `career-application/src/test/java/com/careeros/application/CandidateMatchServiceTest.java`
- Test: `career-web/src/test/java/com/careeros/CandidateMatchApiTest.java`

**Interfaces:**
- Produces: `OfficialJobAdmissionService.classify(List<UUID>, Instant)` and `CandidateMatchService.list(UUID, MatchQuery, Instant)`.
- Candidate match fields: job and organization identity, eligibility status, fit score, employment type, identity-confirmation flag, reason codes, official source URL, and content fingerprint.

- [x] **Step 1: Write failing domain/application tests for include, exclude, and pending identity**

Assert that structurally parsed computer/information/data roles are `VERIFIED/NEEDS_REVIEW`, doctoral hard requirements and teachers are excluded, ambiguous non-technical rows need review, and unknown employment remains visible as a candidate match but not decision-ready. A workbook import must never become `INCLUDED` without an evidence-backed human decision.

- [x] **Step 2: Run the application tests and confirm missing-service failures**

Run `mvn -pl career-application -am -Dtest=OfficialJobAdmissionServiceTest,CandidateMatchServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`.

- [x] **Step 3: Implement classifier and candidate read model**

Use existing `JobContexts`, `JobAdmissions`, `CandidateProfiles`, confirmed candidate facts, `EligibilityEvaluator`, and `FitEvaluator`. Filter hard-ineligible matches by default, keep uncertain results with explicit warnings, and never call the decision-ready assessor for unknown employment identity.

- [x] **Step 4: Wire imported job IDs through classification and add the HTTP endpoint**

Classify every successfully normalized official workbook row after upsert and expose `GET /api/v1/candidates/{candidateId}/job-matches` with bounded pagination.

- [x] **Step 5: Run application and web tests until green**

Run the focused tests, then `mvn -pl career-web -am test`.

### Task 4: Show real matched jobs and verify incremental behavior

**Files:**
- Modify: `career-ui/src/api/contracts.ts`
- Create: `career-ui/src/features/opportunities/candidateMatchApi.ts`
- Create: `career-ui/src/features/opportunities/CandidateMatchQueue.tsx`
- Modify: `career-ui/src/features/opportunities/OpportunitiesPage.tsx`
- Test: `career-ui/src/features/opportunities/OpportunitiesPage.test.tsx`
- Modify: `docs/PHASE4B_WORKBENCH.md`

**Interfaces:**
- Consumes: `/api/v1/candidates/{candidateId}/job-matches`.
- Produces: a visible “符合画像” section split by identity confirmed versus identity pending, with official-source links and clear reasons.

- [x] **Step 1: Write a failing UI test with a real-shape candidate-match response**

Assert that `西溪医院 / 信息中心工作人员`, `符合画像`, `用工身份待确认`, and the official source link render while the trusted-opportunity count remains unchanged.

- [x] **Step 2: Run Vitest and confirm the section is absent**

Run `npm test -- --run src/features/opportunities/OpportunitiesPage.test.tsx` from `career-ui`.

- [x] **Step 3: Implement the match queue and page integration**

Fetch matches with TanStack Query, render eligibility/fit/identity labels, and preserve the existing decision queue as the evidence-complete ledger.

- [x] **Step 4: Run UI and full project tests**

Run the focused Vitest test, `npm test -- --run`, and `mvn test`.

- [x] **Step 5: Run live official-source acceptance twice**

Trigger both official sources, verify the previously unprocessed job-plan workbooks become processed while registration forms are ignored, verify visible profile matches include official URLs, and trigger again to prove stable job counts with no duplicate additions.
