# Isolated Career OS browser fixture

This fixture never uses `compose.yaml`, an existing PostgreSQL container, the migrated default candidate, or production artifacts. All candidate facts, jobs, organizations and evidence are synthetic. The `.invalid` URLs deliberately cannot be mistaken for recruitment evidence.

## Lifecycle

1. Make PostgreSQL 16 available with `docker pull postgres:16-alpine`.
2. Run `node e2e/fixture.mjs create`. This refuses occupied loopback ports `55439` (database) and `18089` (backend), and refuses existing resource names. It creates a new labeled, dedicated bridge network with inter-container communication disabled, a new volume and PostgreSQL container with `restart=no`. Only `127.0.0.1:55439` is published. Docker does not publish host ports on an `internal` network, so that mode is deliberately not used. The returned `.run/e2e-isolated/<run>/manifest.json` contains IDs and endpoints, never credentials. The Docker image ID is pinned in that manifest.
3. Build the application separately. Import `loadIsolatedFixture` and merge `fixture.backendEnvironment()` into the new backend process environment. Supply HTTP Basic credentials separately; the security configuration expects a raw `$2...` BCrypt hash, not a `{bcrypt}` prefix. Bind only the fixture's loopback address and port. Never print the returned environment. This helper does not start Java or run Maven.
4. Start that backend and let its real Flyway migrations finish. The fixture marker occupies a separate schema, so the application's `public` schema is empty before Flyway. Run `node e2e/fixture.mjs seed <manifest>` once. Seed refuses an already seeded candidate, any existing job, or migrations below V88. It does not reset or delete rows.
5. Supply `CAREER_OS_E2E_FIXTURE_MANIFEST=<absolute manifest path>` to the browser test. Use `manifest.candidateId`, `firstJobId`, `secondJobId`, `baseUrl` and `asOf` rather than the migrated default profile or a first-card selector. Seed qualification reference date is explicitly **2026-09-14**; the application still uses its real clock and the test should record actual execution time. Applications close on **2026-12-31**.

The new candidate has independent synthetic education and confirmed basic eligibility facts. Exactly two synthetic jobs exist in this database. The first is T1 (establishment); the second is T2 (formal public-institution employment). Only the second event/job requires political affiliation, gender, an employer-settlement declaration and a social-insurance declaration. Those four candidate values begin unknown/unconfirmed. Query a neutral phrase such as `杭州有哪些岗位` so the query does not filter the second tier out. Browser/API preflight must assert the exact two IDs and order plus all expected pending keys bound to job two; tier order is deliberate, not a substitute for that assertion.

## Independent database evidence

```js
import { loadIsolatedFixture } from './fixture.mjs';
const fixture = loadIsolatedFixture(process.env.CAREER_OS_E2E_FIXTURE_MANIFEST);
const before = fixture.snapshot();
// Browser requests only for user actions.
const after = fixture.snapshot();
```

Snapshots contain the synthetic candidate, its fact confirmations, watch rows, confirmation ledger, canonical `agent_session` rows (ordered IDs, pending questions and profile version), actual persisted decision/eligibility/fit/stability rows, and the two fixture jobs/admission rows. Collections are named `sessions`, `decisions`, `eligibilityAssessments`, `fitAssessments` and `stabilityAssessments`; database fields remain snake_case. Inspect decision `profile_version` and `eligibility_assessment_id` against the actual eligibility row/status/rules rather than inferring recomputation from ledger stage alone.

Immediately before inserting the synthetic candidate, the one-shot seed stores a SHA-256 baseline of the migrated default candidate's profile, fact confirmations, education, employment, sessions, watchlist, confirmation ledger, assessments and opportunities. `originalCandidatePreservation` exports only candidate ID, baseline/current hashes, capture time and `unchanged`; it never exports the original candidate's personal values. Every later snapshot asserts that hash is unchanged, so writes or recomputation accidentally targeting the migrated default candidate invalidate the run. A row hash cannot detect purely read-only access: the browser test must separately check candidate IDs in requests. Configure the browser to use the manifest's synthetic ID before any candidate-facing request.

Every helper operation checks container/network/volume task labels, pinned image ID, loopback port, `current_database`, database role, marker IDs, exact synthetic candidate name and two-job boundary. `connection-ledger.jsonl` records targets and operations without credentials. There is no arbitrary SQL-write export. Backend environment explicitly disables acquisition, LLM, dynamic tools and scripted tools.

For genuine recomputation failure, first obtain a live session/profile version with an actually unconfirmed fact. Then call `fixture.setRecomputeFailure(true)`, which changes **only** the second synthetic job's enum to an invalid value. Submit a fresh confirmation through the browser request layer, expect `RECORDED_RECOMPUTE_DEFERRED`, and inspect the persisted fact and single `WRITTEN` ledger row. Restore in a `finally` block with `fixture.setRecomputeFailure(false)`, then retry the identical request/key and expect `RECOMPUTED`, one ledger row and no second profile-version increment. The helper asserts injection has not changed candidate or admission records. Do not query a new session while the invalid enum is installed: query itself must fail. Re-confirming an already confirmed identical value cannot exercise this failure path.

Containers and volumes are intentionally retained for evidence. No cleanup, engine restart, or existing-resource access is built into this helper. Stop/removal requires a separately reviewed command targeting the exact task-labeled resources in the manifest. Do not use a broad Docker prune command.

## Complete browser runner

`run-closed-loop.mjs` starts the packaged application, verifies real authenticated health, lets Flyway run,
calls the one-shot seed, and executes the existing strengthened `closed-loop.mjs`. It generates a random
one-process account and BCrypt hash via `FixturePasswordHash.java`; no default credential is accepted.
It terminates only the exact Java child it started when the run finishes. PostgreSQL evidence remains.

Prepare the actual frontend build and backend package first. Set these process-local environment variables:

- `JAVA_HOME`: a Java 21 JDK (source-file launch is used for the hash helper).
- `CAREER_OS_E2E_FIXTURE_MANIFEST`: the absolute path from a fresh `node e2e/fixture.mjs create`.
- `E2E_BCRYPT_CLASSPATH`: the resolved `spring-security-crypto` and `spring-jcl` JAR paths, separated by the
  platform classpath delimiter (`;` on Windows, `:` elsewhere). Use the versions resolved by the project.
- `E2E_PLAYWRIGHT_MODULE`, if Playwright is installed outside normal Node module resolution: absolute
  path to its `index.mjs`. This allows a bundled runtime without modifying project dependencies.
- `CHROME_PATH`, if no Playwright-managed Chromium is installed: path to a local Chromium-compatible
  executable. It runs headlessly with a fresh isolated context, not a personal browser profile.

Then run `node e2e/run-closed-loop.mjs`. Do not separately seed before this runner.
The browser report, screenshots, business responses, candidate-scoped request URLs, independent DB snapshots,
actual Git SHA/worktree state and packaged JAR SHA-256 are saved inside the manifest run directory.
The script fails at the first incorrect business result. A failed or completed fixture is evidence, not a
resettable starting point; stop its exact task-labeled container after review and create a new one to rerun.
This test is date-bounded synthetic evidence (2026-09-14 to 2026-12-31), not an assertion of historical coverage.
