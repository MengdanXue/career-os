# Official Job Fact Fusion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fuse official announcement rules and workbook row facts into a complete, evidence-backed in-app job detail for the existing Zhejiang and Hangzhou HRSS sources.

**Architecture:** Parse announcement-wide rules and job-row facts independently, persist field-level provenance, then assemble a read model for the candidate detail page. Processing is versioned so unchanged historical documents are reprocessed after parser upgrades without changing stable job identity.

**Tech Stack:** Java 21, Spring Boot 3.5, Apache POI, Jsoup, PostgreSQL 16, Flyway, React 19, TypeScript, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-08-20-official-job-fact-fusion-design.md`

## Global Constraints

- Scope is limited to the existing Zhejiang HRSS and Hangzhou HRSS sources.
- Hard eligibility facts must come from deterministic parsing and official evidence, not an LLM-only conclusion.
- `VERIFIED` means critical fields have evidence or an explicit not-applicable state; workbook import alone is `NORMALIZED`.
- `duties` must not contain qualification conditions.
- Parser upgrades must reprocess unchanged documents without duplicating jobs.
- A failed attachment reprocess must preserve the last successful active snapshot.

---

### Task 1: Persist the official fact model

**Files:**
- Create: `career-infrastructure/src/main/resources/db/migration/V13__official_job_fact_fusion.sql`
- Create: `career-domain/src/main/java/com/careeros/domain/JobFieldEvidence.java`
- Modify: `career-domain/src/main/java/com/careeros/domain/DomainEnums.java`
- Modify: `career-domain/src/main/java/com/careeros/domain/JobPosting.java`
- Modify: `career-domain/src/main/java/com/careeros/domain/RecruitmentEvent.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/JpaModels.java`
- Test: `career-infrastructure/src/test/java/com/careeros/infrastructure/MigrationIntegrationTest.java`

**Interfaces:**
- Produces: `JobFieldEvidence(jobPostingId, fieldName, factStatus, evidenceFragmentId, rawValue, normalizedValue, extractorVersion, createdAt)`.
- Produces: event-level process and policy fields plus job-level workbook fields defined in the spec.

- [ ] Write a migration integration test asserting the V13 columns, `job_field_evidence` foreign keys, uniqueness, and valid `fact_status` constraint.
- [ ] Run `mvn -q -pl career-infrastructure -am -Dtest=MigrationIntegrationTest test` and verify it fails because V13 does not exist.
- [ ] Add the V13 migration and matching domain/JPA fields. Preserve existing `ESTABLISHMENT` values while adding `PUBLIC_INSTITUTION_FORMAL`.
- [ ] Run the migration test and affected domain tests; verify they pass.
- [ ] Commit with `feat: add official job fact model`.

### Task 2: Parse announcement-wide rules with evidence

**Files:**
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/OfficialAnnouncementFactParser.java`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/OfficialAnnouncementFactService.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/Phase2DocumentProcessor.java`
- Test: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/OfficialAnnouncementFactParserTest.java`
- Test fixture: `var/artifacts/8d/8d49784991dc8a57dfcf7a7e834612ecc281ed93b8c26e1d4c08dd162b335945`

**Interfaces:**
- Produces: `OfficialAnnouncementFacts` containing publication/application/process dates, registration URL, common graduate/overseas/experience rules, exam rules, employment statement, and located evidence excerpts.
- Consumes: canonical announcement URI, HTML bytes, capture time, and existing `OFFICIAL_NOTICE` evidence.

- [ ] Write parser tests against the captured Hangzhou unified recruitment announcement for publication date `2026-03-17`, registration `2026-03-19T09:00` through `2026-03-25T16:00`, age reference `2026-03-19`, written exam date `2026-04-25`, two exam subjects, overseas credential rule, and employment statement.
- [ ] Run the focused parser test and verify the missing parser failure.
- [ ] Implement deterministic Jsoup/text regex parsing with evidence excerpts and no cross-announcement fallback.
- [ ] Persist parsed facts on the matching announcement recruitment event and attach notice evidence.
- [ ] Run focused and extraction tests; verify they pass.
- [ ] Commit with `feat: parse official announcement rules`.

### Task 3: Parse complete workbook rows and field evidence

**Files:**
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/OfficialWorkbookEvidenceService.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/OfficialExcelImportService.java`
- Modify: `career-application/src/main/java/com/careeros/application/JobUpsertService.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/DefaultJobUpsertService.java`
- Test: `career-infrastructure/src/test/java/com/careeros/infrastructure/persistence/OfficialExcelImportServiceTest.java`
- Test: `career-infrastructure/src/test/java/com/careeros/infrastructure/persistence/DefaultJobUpsertServiceTest.java`

**Interfaces:**
- Produces: complete `NormalizedJob` row facts and a map from field name to located spreadsheet evidence.
- Consumes: announcement evidence, workbook URI, sheet name, row number, normalized header, raw cell value.

- [ ] Add a failing Golden Job test for row 144 of the captured Hangzhou workbook, asserting code, category, grade, headcount, education, degree, majors, age, other requirements, ratio, professional test and phone.
- [ ] Add failing tests proving professional parentheticals remain intact and “other requirements” are not written into `duties`.
- [ ] Run focused tests and verify the new assertions fail.
- [ ] Add header aliases and field-specific parsers; split work experience, graduate scope, professional title and remaining requirements without discarding raw text.
- [ ] Create one `OFFICIAL_ATTACHMENT` evidence per workbook and spreadsheet fragments with `sheetName`, `rowNumber`, `columnName` locators; persist `job_field_evidence` after upsert.
- [ ] Run focused persistence tests and verify they pass.
- [ ] Commit with `feat: extract complete workbook job facts`.

### Task 4: Fuse facts and enforce truthful verification

**Files:**
- Create: `career-application/src/main/java/com/careeros/application/OfficialJobFactAssembler.java`
- Create: `career-application/src/main/java/com/careeros/application/OfficialJobCompletenessPolicy.java`
- Modify: `career-application/src/main/java/com/careeros/application/OfficialJobAdmissionService.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/JpaJobAdmissionStore.java`
- Test: `career-application/src/test/java/com/careeros/application/OfficialJobCompletenessPolicyTest.java`
- Test: `career-application/src/test/java/com/careeros/application/OfficialJobAdmissionServiceTest.java`

**Interfaces:**
- Produces: `OfficialJobCompleteness(status, percent, missingFields, conflicts)`.
- Produces: fused job detail where row facts override event facts and every value has a fact status.

- [ ] Write failing tests showing a parsed workbook without field evidence remains `NORMALIZED`, a complete evidenced job becomes `VERIFIED`, and a conflict becomes `REVIEW_REQUIRED`.
- [ ] Run the focused tests and verify failure under the current unconditional `VERIFIED` behavior.
- [ ] Implement critical-field completeness, explicit-empty semantics, evidence coverage and conflict handling.
- [ ] Update admission classification so target scope and data quality are independent.
- [ ] Run application and admission-store tests; verify they pass.
- [ ] Commit with `fix: require evidence for verified jobs`.

### Task 5: Reprocess unchanged documents when the processor changes

**Files:**
- Modify: `career-application/src/main/java/com/careeros/application/AcquiredDocumentProcessor.java`
- Modify: `career-application/src/main/java/com/careeros/application/AcquisitionService.java`
- Modify: `career-domain/src/main/java/com/careeros/domain/acquisition/AcquiredDocument.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/AcquisitionJpaModels.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/JpaAcquisitionStore.java`
- Test: `career-application/src/test/java/com/careeros/application/AcquisitionServiceTest.java`
- Test: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/AcquisitionPostgresIntegrationTest.java`

**Interfaces:**
- Produces: processor descriptor version `official-fact-fusion-v1` and persisted `last_processor_version`.

- [ ] Write failing tests proving identical bytes are processed when the processor version changes, skipped when both content and version match, and only marked processed after successful completion.
- [ ] Run focused acquisition tests and verify they fail under content-only processing.
- [ ] Implement version-aware processing without emitting a false content-change notification.
- [ ] Add PostgreSQL coverage for successful upgrade and failed-upgrade snapshot preservation.
- [ ] Run acquisition tests and verify they pass.
- [ ] Commit with `feat: version acquired document processing`.

### Task 6: Expose a complete official job detail API

**Files:**
- Modify: `career-application/src/main/java/com/careeros/application/CandidateMatchService.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/JpaDecisionStore.java`
- Modify: `career-web/src/test/java/com/careeros/CandidateMatchApiTest.java`
- Test: `career-application/src/test/java/com/careeros/application/CandidateMatchServiceTest.java`

**Interfaces:**
- Produces: `officialFacts`, `recruitmentProcess`, `qualitySummary`, and `evidence` in each candidate match detail.

- [ ] Write failing service/API assertions for all four detail sections and the Golden Job fields.
- [ ] Run focused tests and verify the response lacks the new sections.
- [ ] Add the read model and batched evidence lookup without per-job N+1 queries.
- [ ] Run focused tests and verify stable JSON and source ordering.
- [ ] Commit with `feat: expose evidence-backed job details`.

### Task 7: Render the complete in-app decision page

**Files:**
- Modify: `career-ui/src/features/opportunities/candidateMatchApi.ts`
- Modify: `career-ui/src/features/opportunities/OfficialJobDetail.tsx`
- Modify: `career-ui/src/styles/opportunities.css`
- Test: `career-ui/src/features/opportunities/OfficialJobDetail.test.tsx`
- Test: `career-ui/src/features/opportunities/OpportunitiesPage.test.tsx`

**Interfaces:**
- Consumes: the four-section detail read model from Task 6.
- Produces: one accessible in-app page for conclusion, conditions, process and evidence.

- [ ] Write failing UI tests for complete fields, `NOT_REQUIRED` copy, public-institution formal employment copy, missing-count summary, conflict warning, evidence locators and retained official links.
- [ ] Run `npm test -- --run OfficialJobDetail.test.tsx OpportunitiesPage.test.tsx` and verify failure.
- [ ] Implement the four-section detail with concise Chinese labels and no raw enum leakage.
- [ ] Add responsive styling and preserve focus/keyboard behavior.
- [ ] Run focused and full UI tests; verify they pass.
- [ ] Commit with `feat: present complete official job dossier`.

### Task 8: Reprocess production data and verify the Golden Job

**Files:**
- Modify: `docs/current-gap-analysis.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: source codes `HZ_HRSS_INSTITUTION` and `ZJ_HRSS_INSTITUTION`.
- Produces: refreshed database facts and a documented acceptance result.

- [ ] Package and restart the application, then invoke both official source scans.
- [ ] Verify all active Excel attachments record `official-fact-fusion-v1` and processing failures do not deactivate the prior snapshot.
- [ ] Query completeness counts and confirm `VERIFIED` no longer means merely imported.
- [ ] Open the Golden Job in a real browser and verify all required fields, evidence locations, mobile layout and source links.
- [ ] Run the full Maven suite and all UI tests.
- [ ] Update the gap analysis with measured before/after counts and acceptance evidence.
- [ ] Request final code review, resolve Critical/Important findings, run `git diff --check`, and commit with `docs: record official fact fusion acceptance`.
- [ ] Push `codex/phase4b-career-workbench` to the existing private remote.
