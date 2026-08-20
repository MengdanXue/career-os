# Phase 4B Career Decision Workbench Design

**Status:** Approved in chat on 2026-08-20

**Scope:** A non-technical, locally runnable web workbench over the accepted Phase 0–4A backend. It makes candidate setup, official-source updates, document import, T1/T2/T3 ranking, evidence review, and bounded Agent queries usable without Swagger.

## 1. Goal

Phase 4B turns Career OS from an accepted backend into a product the first real user can operate at `http://localhost:8080`.

The workbench must let that user:

1. open the system with one local command;
2. confirm or update the candidate profile used for decisions;
3. run the two existing Zhejiang/Hangzhou official sources and import official files;
4. see only meaningful acquisition changes and review-required items;
5. browse a tier-separated opportunity pool;
6. understand why a job is eligible, excluded, stable, or uncertain;
7. ask bounded Chinese-language questions without configuring a model key.

The workbench is not an administrative database editor. Interface language describes user decisions and evidence rather than entities, repositories, evaluator versions, or crawler internals.

## 2. Delivery Boundary

### Included

- React and TypeScript single-page application served by the existing Spring Boot process.
- First-use profile confirmation and later profile editing.
- Today view with meaningful changes, source health, review count, and upcoming deadlines.
- Opportunity pool with T1, T2, T3, and excluded/needs-review separation.
- Job dossier with eligibility, fit, stability, evidence coverage, warnings, and source link.
- Bounded Agent query using the accepted Phase 4A endpoint.
- Data update center for the two existing official sources, XLS/XLSX import, and HTML/PDF submission.
- Review Queue summary and navigation to evidence details.
- Reproducible frontend build inside the Maven package.
- Local PostgreSQL Compose service and Windows start/stop scripts.
- Unit, component, accessibility, responsive, browser-flow, backend, and packaging verification.

### Excluded

- New district, university, hospital, SOE, Playwright, login, or CAPTCHA source adapters.
- Full 2025–2026 historical backfill and 2027 forecasting.
- Automatic application submission, external messages, email, WeChat, or calendar writes.
- Resume generation, interview preparation, deadline notification delivery, or multi-user authentication.
- Editing raw Organization, RecruitmentEvent, Evidence, or database records from the UI.
- Changing Phase 4A eligibility, fit, stability, tier, or recommendation formulas.

Source expansion and historical backfill remain a separately designed phase after the workbench is accepted.

## 3. Chosen Architecture

The UI is a React 19.2 + TypeScript application built by Vite 8. It lives in a new top-level `career-ui` directory but is packaged into `career-web` under Spring Boot static resources. The browser calls same-origin `/api` endpoints, so there is no production CORS configuration and no second production server.

Node.js 24 LTS is provisioned by Maven's frontend plugin; the repository lockfile pins exact JavaScript dependencies. The user does not install Node to run the packaged application. Development may run Vite separately with an `/api` proxy to Spring Boot.

```text
Browser :8080
    |
    +-- /, /opportunities, /updates, /profile  -> React SPA
    |
    +-- /api/v1/...                            -> Decision/Profile/Import APIs
    +-- /api/acquisition/...                   -> Source/Run/Change APIs
    |
Spring Boot
    +-- existing application services
    +-- PostgreSQL 16
    +-- filesystem artifact store
```

No business rule is implemented in TypeScript. The frontend may format, filter within an already fetched page, and derive display labels; all eligibility, score, tier, evidence, idempotence, and Agent results come from backend responses.

## 4. Frontend Boundaries

`career-ui/src` is split by responsibility:

- `app/`: route composition, global providers, error boundary, and shell.
- `api/`: typed fetch client, response contracts, RFC 9457 problem parsing, and query keys.
- `features/today/`: daily changes, urgent deadlines, source health, and review summary.
- `features/opportunities/`: tier filters, job rows, pagination, and dossier presentation.
- `features/agent/`: question composer and answer/decision rendering.
- `features/updates/`: source runs and official document imports.
- `features/profile/`: first-use confirmation and profile form.
- `components/`: shared buttons, fields, status chips, decision rail, progress bars, empty/error states, and accessible dialog/drawer primitives.
- `styles/`: tokens, reset, type, shell, and reduced-motion rules.

Feature modules consume only functions exported by `api/` and reusable primitives exported by `components/`. They do not call `fetch` directly and do not import from other feature internals.

## 5. Information Architecture

The product has four top-level destinations and one persistent Agent entry:

1. **今天** — what changed and what needs attention.
2. **机会池** — T1/T2/T3 decision queue and job dossiers.
3. **更新岗位库** — official-source runs and manual document imports.
4. **我的资料** — candidate facts and preferences.
5. **问 Career OS** — a docked composer available from Today and Opportunity Pool.

Desktop uses a two-pane decision workspace rather than a grid of generic dashboard cards:

```text
┌──────────────────────────────────────────────────────────────────────┐
│ CAREER OS     今天  机会池  更新岗位库  我的资料      数据更新状态 │
├───────────────────────────────┬──────────────────────────────────────┤
│ 决策队列                      │ 岗位档案                             │
│ [T1 03] [T2 07] [T3 12]      │ 信息中心工作人员                    │
│                               │ 资格 ━━━ 证据 ━━━ T1 ━━━ 09/18     │
│ ▌T1 杭州市××信息中心          │                                      │
│   匹配 78  稳定 40  证据 70% │ 硬资格 / 匹配度 / 稳定性 / 证据     │
│ ▌T1 ××医院信息中心            │                                      │
│ ▌T2 ××大学信息化岗            │ 来源、风险和下一步                   │
├───────────────────────────────┴──────────────────────────────────────┤
│ 问 Career OS：杭州还有哪些值得优先准备的信息化岗位？        [发送] │
└──────────────────────────────────────────────────────────────────────┘
```

At widths below 760 px the queue and dossier become separate routes, top navigation becomes a four-item bottom bar, the Agent composer stays above the safe area, and tables become labeled definition lists. No horizontal page scrolling is allowed at 390 px.

## 6. Visual System

The subject is a private career decision instrument for semi-public technical jobs, not a recruiting marketplace. The visual language combines a municipal technical control panel with a personal application dossier.

### Color tokens

- `porcelain-50 #EEF4F5`: calm workspace background.
- `paper-0 #FCFEFE`: dossier surfaces.
- `archive-900 #18313B`: primary text and structural ink.
- `tide-600 #0B7A80`: active decision and focus accent.
- `evidence-600 #2F5F73`: evidence and source metadata.
- `caution-600 #B97822`: unknown, incomplete, and needs-review states.
- `blocker-600 #A5423A`: hard ineligibility and failed operations only.
- `eligible-600 #2F7758`: verified eligible and completed operations.

All text/background combinations meet WCAG AA contrast. Tier is never encoded by color alone; every tier includes its literal `T1`, `T2`, `T3`, or `排除` label.

### Type

- Display: `Noto Serif SC`, `Source Han Serif SC`, `Songti SC`, serif, used only for the selected job title and Today thesis.
- Body: `Noto Sans SC`, `Microsoft YaHei UI`, `PingFang SC`, sans-serif.
- Data: `IBM Plex Mono`, `Cascadia Mono`, monospace, used for scores, dates, fingerprints, and evidence coordinates.

The application performs no runtime font download. Installed faces are preferred and system fallbacks keep Chinese rendering reliable offline.

### Signature element: decision rail

Every job has one semantic rail:

```text
资格状态  ━━━  证据覆盖  ━━━  机会层级  ━━━  截止日期
ELIGIBLE       70%            T1             09/18
```

The rail is both the dominant visual device and the summary of the actual decision pipeline. It replaces decorative charts on list rows. On the dossier page it expands into the individual eligibility, fit, and stability dimensions.

### Motion

One orchestrated transition occurs when a queue row opens its dossier: the rail expands into the dimension sections. Other state changes use a 120–180 ms opacity transition. `prefers-reduced-motion` removes expansion and smooth scrolling. There are no ambient gradients, bouncing icons, or decorative loading animations.

### Design self-critique

A dense broadsheet layout would look like a current AI design default and would make evidence review tiring. The selected direction therefore uses generous grouped surfaces and a single strong rail instead of hairline-heavy newspaper columns. A generic analytics dashboard would overemphasize totals; the chosen two-pane queue keeps the primary action—deciding which job deserves attention—visible at all times.

## 7. Core User Flows

### 7.1 First use

1. The shell requests `GET /api/v1/candidates`.
2. If no candidate exists, the Profile flow creates one through `POST /api/v1/candidates`.
3. If a seeded candidate exists, the flow shows its facts for confirmation before presenting rankings.
4. Required fields are name, birth year/month, education, at least one major, preferred location, accepted employment type, and a profile version generated as `profile-ui-<UUID>` before the save request.
5. Optional skills, research topics, target job families, and preferred organization types are visibly marked optional.
6. Saving returns to Today and invalidates all candidate-specific queries.

The UI generates one new `profileVersion` when an edit session is submitted and reuses it if that same request must be retried. Backend decision identity continues to use that version; existing snapshots remain historical.

### 7.2 Today

Today is an attention queue, not a statistics dashboard. It shows:

- changes since the last seven local days, grouped as new, changed, and deactivated;
- pending review count;
- enabled source health and last completed result;
- applications ending within fourteen days among ranked non-excluded jobs;
- a clear empty state directing the user to update the job library when there are no jobs.

The browser stores only the last-seen acquisition cursor and selected candidate ID in local storage. Candidate facts, decisions, and job data are never treated as browser-authoritative state.

### 7.3 Opportunity pool

The initial tab is T1. T1, T2, and T3 always have separate tabs; excluded items are behind a `待确认与排除` tab. Filters are location, job family, and free-text title/organization within loaded results. Server pagination remains authoritative.

List order exactly follows the Phase 4A ranking response. The UI cannot drag or manually reorder ranked jobs. A selected row opens the dossier without losing current filters or page.

### 7.4 Job dossier

The dossier renders:

- job, organization, location, and source;
- decision rail;
- recommendation and non-probability disclaimer;
- each hard eligibility result;
- fit dimensions with achieved/maximum points and unknown state;
- stability dimensions with achieved/maximum points and evidence coverage;
- deterministic explanation and warnings;
- evidence identifiers and source URL;
- version metadata under a collapsed `评估记录` section.

Unknown evidence is phrased as `缺少可核验证据` rather than `0 分`. A hard blocker appears before all soft scores. The page never shows an aggregate admission probability.

### 7.5 Agent query

The composer posts to `/api/v1/candidates/{candidateId}/agent-queries`. Suggested prompts are concrete filters such as `找杭州 T1 信息化岗位` and `有哪些数据岗位需要先确认资格？`.

The answer is rendered above structured decision rows. `modelPhrased` and `fallbackUsed` are translated into plain status copy; model terminology is not shown. The structured jobs remain visible even if the answer text is unavailable. The composer has no tools for applying, editing data, or sending external messages.

### 7.6 Update job library

The update center lists the two existing official sources with enabled state, last success/failure, and a `立即检查` action. A manual source run shows its real terminal counters and does not claim completion while running or partially failed.

Manual import has two paths:

- XLS/XLSX: announcement title, official source URL, year, location, event type, optional dates/unit name, and file; posts to `/api/v1/imports/excel`.
- HTML/PDF: source title, official source URL, capture time, optional organization/event selection, and file; posts to `/api/v1/extractions` and links to Review Queue when human review is required.

Unsupported formats, oversized files, duplicate reuse, partial source failure, and pending review each get a distinct result message with the next useful action.

Pending reviews appear in a `待复核` subsection of Update Job Library. A review dossier shows the proposed structured jobs beside evidence fragments and issue reasons. The user can confirm, reject, request more evidence, or correct the structured proposal through labeled fields; raw JSON is never required. Review actions send the displayed optimistic-lock version, preserve the source identity, and show a refresh action on conflict instead of overwriting another resolution.

## 8. API Use and Additions

The workbench reuses:

- candidate resources under `/api/v1/candidates`;
- decisions and rankings under `/api/v1/candidates/{candidateId}/job-decisions`;
- Agent queries under `/api/v1/candidates/{candidateId}/agent-queries`;
- acquisition sources, runs, and changes under `/api/acquisition`;
- Excel import under `/api/v1/imports/excel`;
- extraction and review resources under `/api/v1/extractions` and `/api/v1/reviews`.

One read model is added:

```http
GET /api/v1/candidates/{candidateId}/workbench-summary
```

It returns:

- candidate identity and profile completeness;
- tier counts for current active jobs;
- up to five upcoming non-excluded deadlines within fourteen days;
- acquisition change counts for the last seven days;
- enabled source health summary;
- pending review count.

The read model delegates ranking and source/review queries to existing application ports. It does not reproduce decision rules. Counts and timestamps include an `asOf` instant. If one secondary subsystem is unavailable, the response returns its section with `available=false` and a stable problem summary while preserving other sections.

The server adds an SPA fallback only for browser routes that do not begin with `/api`, `/actuator`, `/v3/api-docs`, `/swagger-ui`, or a filename containing a dot. Unknown API routes continue to return API errors and are never rewritten to HTML.

## 9. State, Loading, Empty, and Error Semantics

- URL search parameters own opportunity tier, location, job family, page, and selected job ID.
- Server data uses a query cache with explicit invalidation after profile save, source run, import, or review action.
- Loading retains the previous list and marks it updating; it does not replace the page with a spinner.
- Empty states say why the section is empty and offer one relevant action.
- RFC 9457 responses display their stable `code` and translated detail in an expandable diagnostic area.
- Network failure offers `重试` and preserves entered form data.
- `401` is not designed because Phase 4B is local single-user software with no authentication.
- Failed or partial acquisition runs never appear as successful green states.

## 10. Accessibility and Privacy

- Semantic landmarks, headings, lists, tables, dialogs, and forms are used before ARIA.
- All actions are reachable by keyboard with visible focus.
- Dossier drawers trap focus, close with Escape, restore focus to the originating row, and become normal pages on mobile.
- Form errors are associated with fields and summarized at the top.
- Status is conveyed with text and icon shape in addition to color.
- At 200% zoom all flows remain operable without content loss.
- No analytics, trackers, runtime CDNs, remote fonts, or client-side model calls are added.
- Candidate facts remain in PostgreSQL and are not written to local storage.
- Source excerpts are escaped and never inserted as raw HTML.

## 11. Local Runtime

`compose.yaml` defines PostgreSQL 16 with a health check, named volume, local-only credentials, and port `5432`. It does not package or expose the Career OS application container in this phase.

`start-career-os.ps1`:

1. resolves its repository directory explicitly;
2. starts only the named PostgreSQL service;
3. waits for database health with a bounded timeout;
4. builds the application when the executable JAR is missing or older than tracked source files;
5. starts the exact Career OS JAR in a hidden process;
6. writes its PID beneath repository-local `.run`;
7. waits for `/actuator/health` and opens `http://localhost:8080`;
8. prints a plain-language failure and log location on timeout.

`stop-career-os.ps1` validates that the stored PID belongs to the repository's Career OS JAR before stopping it, then stops the named database service without deleting the volume. Neither script deletes data. Advanced users may continue to run Maven and PostgreSQL manually.

## 12. Build and Dependency Policy

- Node.js 24 LTS is downloaded into Maven's build directory by `frontend-maven-plugin`.
- JavaScript dependencies are installed with `npm ci` from committed `package-lock.json`.
- React 19.2 and Vite 8 are the only framework/build foundations.
- Reusable accessibility primitives may use Radix UI; icons use Lucide; data fetching/cache uses TanStack Query.
- No comprehensive visual component kit, chart dashboard kit, state-management framework, CSS-in-JS runtime, or external CDN is introduced.
- Custom CSS properties and scoped feature styles implement the approved visual system.
- Production assets receive content hashes and are copied to `career-web/target/classes/static` without writing generated files into `src/main/resources`.
- `mvn test` runs frontend type checking and unit/component tests in addition to Java tests.
- `mvn package` creates one Spring Boot JAR containing the production frontend.

The runtime choices are pinned to currently supported foundations: Node 24 is LTS, React's official current major is 19.2, and Vite 8 supports the selected Node line.

## 13. Testing Strategy

All behavior follows red-green-refactor.

### Frontend unit and component tests

- Problem Detail parsing and user-facing messages.
- Decision rail state and non-color labels.
- T1/T2/T3 tab isolation and URL filter serialization.
- Empty, loading, unavailable, partial-failure, and evidence-unknown states.
- Candidate form mapping, stable retry version, and a new version for a later edit session.
- Agent no-model, model-phrased, and fallback presentations.
- Import form validation and duplicate-reuse result.
- Review confirm, correction, rejection, need-more-evidence, and optimistic-conflict states.

Tests use Vitest, React Testing Library, and Mock Service Worker. They assert user-visible behavior rather than component implementation details.

### Backend tests

- Workbench summary aggregation and partial subsystem availability.
- SPA fallback exclusions for API, actuator, OpenAPI, Swagger, and asset paths.
- Static `index.html` and hashed assets are present in the packaged JAR.
- Existing API and architecture tests remain green.

### Browser flows

Playwright runs against a production frontend build with controlled API responses and covers:

1. first-use profile confirmation;
2. T1/T2/T3 filtering and dossier navigation;
3. hard blocker and unknown-evidence presentation;
4. Agent question and deterministic answer;
5. official source update result;
6. Excel and PDF/HTML import result;
7. 1440×900 desktop and 390×844 mobile layouts;
8. keyboard dossier flow and automated accessibility checks.

A final local smoke test starts PostgreSQL and the packaged JAR, opens the real `/actuator/health`, `/`, and `/api/v1/candidates`, and confirms the SPA and backend are served by the same process.

## 14. Acceptance Criteria

1. `start-career-os.ps1` starts PostgreSQL, builds when needed, starts Career OS, and opens a healthy workbench without requiring Node, Swagger, or a model key.
2. A seeded or newly created candidate can be confirmed and edited; saving produces a new profile version and later decisions use it.
3. Today accurately distinguishes new, changed, deactivated, pending-review, partial-failure, and no-data states.
4. Opportunity Pool never mixes T1, T2, and T3 in one unlabelled ranking and keeps excluded jobs out of recommended tabs.
5. A job dossier exposes hard eligibility before soft scores and shows evidence coverage, unknown facts, warnings, versions, source, and the non-probability disclaimer.
6. Reopening or refreshing the same current decision reuses the same backend snapshot.
7. Agent queries work with the model disabled and retain structured decisions when phrasing falls back.
8. Both official sources can be triggered, XLS/XLSX plus HTML/PDF files can be submitted from plain-language forms, and pending proposals can be reviewed without editing JSON.
9. Desktop, 390 px mobile, keyboard, reduced-motion, 200% zoom, and WCAG AA automated checks pass.
10. The production JAR contains the frontend, serves browser routes without rewriting API errors, and passes the complete Java, frontend, Playwright, PostgreSQL migration, and package verification suites.

## 15. Delivery Sequence

Implementation proceeds in independently testable increments:

1. reproducible frontend build and SPA serving;
2. typed API client and visual primitives;
3. profile confirmation;
4. opportunity queue and dossier;
5. Agent composer;
6. Today read model and screen;
7. update/import center;
8. local start/stop experience;
9. browser, accessibility, responsive, package, documentation, and private-remote acceptance.

Passing Phase 4B means the existing Career OS is usable as local single-user software. It does not mean source coverage, historical backfill, forecasting, notifications, or application execution are complete.
