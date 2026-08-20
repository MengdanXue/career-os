# Phase 4B Career Decision Workbench Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn Career OS into a locally runnable, non-technical career decision workbench that highlights daily changes, separates T1/T2/T3 opportunities, exposes evidence and blockers, supports bounded agent answers, and lets the user update/review official job data.

**Architecture:** Add a React/TypeScript SPA in `career-ui`, built by Maven into the existing Spring Boot application and served from the same origin. Reuse the Phase 4A decision, acquisition, extraction, review, and agent APIs; add one read-model endpoint for the Today page. Keep business rules in Java and presentation logic in React.

**Tech Stack:** Java 21, Spring Boot, Maven, PostgreSQL 16, React 19.2.8, TypeScript 7.0.2, Vite 8.2.2, TanStack Query 5.101.4, React Router 7.18.2, Radix Dialog 1.1.23, Vitest 4.1.11, Testing Library 16.3.2, MSW 2.15.0, Playwright 1.62.1, axe-core 4.13.0.

**Spec:** `docs/superpowers/specs/2026-08-20-phase4b-career-workbench-design.md`

## Global Constraints

- [ ] Preserve all existing Career OS APIs and Phase 4A behavior.
- [ ] Keep eligibility and scoring logic in the backend; the SPA only presents returned decisions.
- [ ] Use same-origin `/api` calls and a safe SPA fallback that never intercepts API, actuator, OpenAPI, Swagger, or static-extension paths.
- [ ] Make unknown evidence explicit; never convert missing evidence to zero.
- [ ] Keep the product local-first, anonymous, and single-user in this phase.
- [ ] Follow RED → GREEN → REFACTOR for each task and commit only verified slices.
- [ ] Execute inline because this task has not authorized subagents and the user requested uninterrupted progress.

## Task 1: Reproducible frontend build and safe SPA serving

**Files:**

- Create: `career-ui/package.json`
- Create: `career-ui/package-lock.json`
- Create: `career-ui/index.html`
- Create: `career-ui/tsconfig.json`
- Create: `career-ui/tsconfig.app.json`
- Create: `career-ui/tsconfig.node.json`
- Create: `career-ui/vite.config.ts`
- Create: `career-ui/src/main.tsx`
- Create: `career-ui/src/App.tsx`
- Create: `career-ui/src/test/setup.ts`
- Create: `career-ui/src/App.test.tsx`
- Create: `career-web/src/main/java/com/careeros/web/SpaForwardController.java`
- Create: `career-web/src/test/java/com/careeros/web/SpaServingTest.java`
- Modify: `career-web/pom.xml`
- Modify: `.gitignore`

- [ ] Write a frontend test that expects the Career OS product name and the four primary destinations.
- [ ] Write MockMvc tests for `/`, a browser route, `/api/...`, `/actuator/...`, and asset-extension paths.
- [ ] Run the focused tests and confirm they fail for missing shell/fallback behavior.
- [ ] Add exact dependency versions and scripts: `dev`, `build`, `test`, `typecheck`, and `verify`.
- [ ] Configure `frontend-maven-plugin` 1.15.4 to provision Node 24.19.0, run `npm ci`, build resources, and run frontend verification during Maven tests.
- [ ] Implement the minimal React shell and safe server-side forwarding.
- [ ] Run frontend tests, focused Java tests, and a packaged-jar resource check.
- [ ] Commit: `feat(ui): package career workbench shell`

## Task 2: Typed API client, application shell, and decision primitives

**Files:**

- Create: `career-ui/src/api/contracts.ts`
- Create: `career-ui/src/api/http.ts`
- Create: `career-ui/src/api/http.test.ts`
- Create: `career-ui/src/app/AppProviders.tsx`
- Create: `career-ui/src/app/AppShell.tsx`
- Create: `career-ui/src/components/DecisionRail.tsx`
- Create: `career-ui/src/components/DecisionRail.test.tsx`
- Create: `career-ui/src/components/StatusChip.tsx`
- Create: `career-ui/src/components/ScoreBar.tsx`
- Create: `career-ui/src/components/AsyncState.tsx`
- Create: `career-ui/src/styles/tokens.css`
- Create: `career-ui/src/styles/global.css`
- Create: `career-ui/src/styles/shell.css`

- [ ] Test RFC 9457/ProblemDetail conversion to stable `ApiProblem` values.
- [ ] Test network failures as `NETWORK_UNAVAILABLE` without leaking implementation messages.
- [ ] Test Decision Rail eligibility, evidence coverage, tier, and deadline labels with accessible text.
- [ ] Run focused tests and observe RED.
- [ ] Add BrowserRouter, TanStack Query, typed JSON/FormData helpers, and query-key factories.
- [ ] Implement porcelain/archive/tide/evidence/caution/blocker/eligible design tokens, responsive shell navigation, and reduced-motion defaults.
- [ ] Run tests and typecheck; refactor duplicated loading/error UI.
- [ ] Commit: `feat(ui): add typed client and decision visual system`

## Task 3: First-use candidate profile workflow

**Files:**

- Create: `career-ui/src/features/profile/profileApi.ts`
- Create: `career-ui/src/features/profile/profileSchema.ts`
- Create: `career-ui/src/features/profile/ProfileForm.tsx`
- Create: `career-ui/src/features/profile/ProfilePage.tsx`
- Create: `career-ui/src/features/profile/ProfileGate.tsx`
- Create: `career-ui/src/features/profile/ProfilePage.test.tsx`
- Modify: `career-ui/src/App.tsx`

- [ ] Test that an unconfirmed seeded candidate opens a plain-language confirmation gate.
- [ ] Test that a failed retry reuses one `profile-ui-<UUID>` version and the next edit session creates a new version.
- [ ] Test that confirmation stores only the selected candidate and confirmation marker locally.
- [ ] Run focused tests and observe RED.
- [ ] Implement labeled fields, evidence-aware help text, validation, and optimistic-safe submission.
- [ ] Invalidate candidate-specific queries after a successful edit.
- [ ] Run focused tests and typecheck.
- [ ] Commit: `feat(ui): guide candidate profile confirmation`

## Task 4: Tiered opportunity queue and job dossier

**Files:**

- Create: `career-ui/src/features/opportunities/opportunityApi.ts`
- Create: `career-ui/src/features/opportunities/opportunityFilters.ts`
- Create: `career-ui/src/features/opportunities/TierTabs.tsx`
- Create: `career-ui/src/features/opportunities/OpportunityQueue.tsx`
- Create: `career-ui/src/features/opportunities/OpportunityRow.tsx`
- Create: `career-ui/src/features/opportunities/JobDossier.tsx`
- Create: `career-ui/src/features/opportunities/DimensionList.tsx`
- Create: `career-ui/src/features/opportunities/OpportunitiesPage.tsx`
- Create: `career-ui/src/features/opportunities/OpportunitiesPage.test.tsx`
- Create: `career-ui/src/styles/opportunities.css`
- Modify: `career-ui/src/App.tsx`

- [ ] Test that T1/T2/T3 are isolated and excluded/review-needed opportunities are separate.
- [ ] Test that hard blockers appear before fit scores in a dossier.
- [ ] Test missing evidence as “待核实” rather than “0 分”.
- [ ] Run focused tests and observe RED.
- [ ] Implement URL-backed filters, server pagination, desktop two-pane layout, and mobile dossier routing/dialog behavior.
- [ ] Present decision rail, blockers, score dimensions, evidence citations, decision version, and non-guarantee disclaimer.
- [ ] Run focused tests and typecheck.
- [ ] Commit: `feat(ui): browse tiered opportunity dossiers`

## Task 5: Bounded Career OS agent composer

**Files:**

- Create: `career-ui/src/features/agent/agentApi.ts`
- Create: `career-ui/src/features/agent/AgentComposer.tsx`
- Create: `career-ui/src/features/agent/AgentAnswer.tsx`
- Create: `career-ui/src/features/agent/AgentComposer.test.tsx`
- Create: `career-ui/src/styles/agent.css`
- Modify: `career-ui/src/app/AppShell.tsx`

- [ ] Test no-model structured answers with cited jobs and next actions.
- [ ] Test fallback copy that remains useful and does not expose LLM/provider jargon.
- [ ] Run focused tests and observe RED.
- [ ] Implement the persistent composer, bounded question examples, structured results, evidence links, and retry states.
- [ ] Run focused tests and typecheck.
- [ ] Commit: `feat(ui): add bounded career agent composer`

## Task 6: Workbench summary read model and Today page

**Files:**

- Create: `career-application/src/main/java/com/careeros/application/workbench/WorkbenchSummaryService.java`
- Create: `career-application/src/test/java/com/careeros/application/workbench/WorkbenchSummaryServiceTest.java`
- Modify: decision query ports/projections as required without duplicating decision rules
- Create: `career-web/src/main/java/com/careeros/web/workbench/WorkbenchSummaryController.java`
- Create: `career-web/src/main/java/com/careeros/web/workbench/WorkbenchSummaryResponse.java`
- Create: `career-web/src/test/java/com/careeros/web/workbench/WorkbenchSummaryApiTest.java`
- Modify: `career-bootstrap/src/main/java/com/careeros/bootstrap/ApplicationConfiguration.java`
- Create: `career-ui/src/features/today/todayApi.ts`
- Create: `career-ui/src/features/today/TodayPage.tsx`
- Create: `career-ui/src/features/today/TodayPage.test.tsx`
- Create: `career-ui/src/styles/today.css`
- Modify: `career-ui/src/App.tsx`

- [ ] Test aggregation of changes, deadlines, source health, review work, and tier counts.
- [ ] Test that acquisition/review unavailability degrades one section instead of failing the summary.
- [ ] Test `GET /api/v1/candidates/{candidateId}/workbench-summary` contract and authorization-free local behavior.
- [ ] Run focused Java tests and observe RED.
- [ ] Implement orchestration over existing decision/acquisition/review query ports.
- [ ] Test Today attention cards, empty state, and links into filtered destinations; observe RED then implement.
- [ ] Run focused frontend/Java tests.
- [ ] Commit: `feat(workbench): summarize daily career attention`

## Task 7: Data updates, import status, and human review

**Files:**

- Create: `career-ui/src/features/updates/updateApi.ts`
- Create: `career-ui/src/features/updates/SourceList.tsx`
- Create: `career-ui/src/features/updates/ExcelImportForm.tsx`
- Create: `career-ui/src/features/updates/DocumentImportForm.tsx`
- Create: `career-ui/src/features/updates/ReviewQueue.tsx`
- Create: `career-ui/src/features/updates/ReviewDossier.tsx`
- Create: `career-ui/src/features/updates/ReviewProposalForm.tsx`
- Create: `career-ui/src/features/updates/UpdatesPage.tsx`
- Create: `career-ui/src/features/updates/UpdatesPage.test.tsx`
- Create: `career-ui/src/styles/updates.css`
- Modify: `career-ui/src/App.tsx`

- [ ] Test that a partial acquisition run is not presented as full success.
- [ ] Test idempotent document reuse and existing-result navigation.
- [ ] Test review confirmation with `expectedVersion`.
- [ ] Test conflict refresh while preserving entered corrections.
- [ ] Run focused tests and observe RED.
- [ ] Implement source run triggers, bounded polling, Excel/document FormData imports, review filters, and labeled correction fields.
- [ ] Send full corrected payloads for CORRECT and preserve note/evidence fields.
- [ ] Run focused tests and typecheck.
- [ ] Commit: `feat(ui): update and review official job data`

## Task 8: One-command local runtime

**Files:**

- Create: `compose.yaml`
- Create: `scripts/start-career-os.ps1`
- Create: `scripts/stop-career-os.ps1`
- Create: `scripts/Test-LocalRuntime.ps1`
- Modify: `.gitignore`
- Modify: `README.md`

- [ ] Write static contract checks for `postgres:16-alpine`, health checks, actuator probing, exact-process validation, and non-destructive shutdown.
- [ ] Run the PowerShell contract test and observe RED.
- [ ] Implement PostgreSQL startup, 90-second readiness, stale-jar rebuild, hidden exact Java process startup, `.run` PID/log files, health verification, and browser opening.
- [ ] Implement stop logic that validates the recorded process command and never deletes database volumes.
- [ ] Run static checks and a local smoke test twice to prove restartability.
- [ ] Commit: `feat(runtime): start career os with one command`

## Task 9: Browser acceptance, accessibility, documentation, and release verification

**Files:**

- Create: `career-ui/playwright.config.ts`
- Create: `career-ui/e2e/fixtures.ts`
- Create: `career-ui/e2e/first-use.spec.ts`
- Create: `career-ui/e2e/opportunities.spec.ts`
- Create: `career-ui/e2e/agent.spec.ts`
- Create: `career-ui/e2e/updates.spec.ts`
- Create: `career-ui/e2e/responsive-accessibility.spec.ts`
- Create: `docs/PHASE4B_WORKBENCH.md`
- Modify: `README.md`
- Modify any production files required by observed browser failures

- [ ] Write Playwright flows for first-use confirmation, T1 isolation, missing evidence, disclaimer visibility, and update/review status.
- [ ] Add axe checks and a 390×844 keyboard/mobile flow with no horizontal overflow and restored focus after dossier close.
- [ ] Run browser tests and observe RED before fixing observed product gaps.
- [ ] Visually inspect screenshots and remove at least one unnecessary decorative/accessory element if it competes with decision information.
- [ ] Document local start/stop, source update behavior, evidence meanings, known boundaries, and recovery steps.
- [ ] Run final verification:

```powershell
npm ci
npm run verify
npm run test:e2e
mvn test
mvn -DskipTests package
git diff --check
pwsh -NoProfile -File scripts/Test-LocalRuntime.ps1 -Smoke
```

- [ ] Review the diff against every acceptance criterion in the approved spec.
- [ ] Commit: `test(ui): verify career workbench journeys`
- [ ] Push `codex/phase4b-career-workbench` and verify the remote repository remains private.

## Completion Criteria

- [ ] A non-technical user can start Career OS locally with one command and reach a healthy browser UI.
- [ ] First use requires profile confirmation before decision views.
- [ ] Today shows only actionable changes, deadlines, source health, and review work.
- [ ] Opportunities are separated by T1/T2/T3 and dossiers lead with hard blockers and evidence.
- [ ] Agent answers remain useful with no model configured and during model failure.
- [ ] Update/import/review workflows accurately communicate partial, reused, conflicted, and completed states.
- [ ] Browser, frontend, backend, packaging, and local-runtime verification all pass from a clean dependency install.
