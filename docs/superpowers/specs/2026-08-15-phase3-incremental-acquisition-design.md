# Phase 3A Incremental Acquisition Design

**Status:** Approved in chat on 2026-08-15

**Scope:** Two real official recruitment sources, repeatable scheduled acquisition, and an auditable change feed that reuses the accepted Phase 2 extraction and job-delta pipeline.

## 1. Goal

Build the smallest production-shaped acquisition loop for the first Career OS use case: stable computer-technology positions in Zhejiang, with Hangzhou as the priority city.

The loop must:

1. discover recruitment announcements from two official list pages;
2. fetch announcement HTML and supported attachments;
3. detect document additions and content changes without treating repeated polling as new work;
4. route supported content into the existing extraction, evidence, review, and job-upsert services;
5. record every source run and every resulting change;
6. expose only added, updated, and deactivated changes to downstream notification clients.

## 2. First Sources

The first enabled sources are:

| Code | Authority | Entry URL | Region | Mode |
|---|---|---|---|---|
| `ZJ_HRSS_INSTITUTION` | `OFFICIAL_GOVERNMENT` | `https://rlsbt.zj.gov.cn/col/col1229743683/index.html` | Zhejiang | `STATIC_HTML` |
| `HZ_HRSS_INSTITUTION` | `OFFICIAL_GOVERNMENT` | `https://hrss.hangzhou.gov.cn/col/col1229782005/index.html` | Hangzhou | `STATIC_HTML` |

Both source definitions are database records seeded by Flyway. URLs, selectors, frequency, and enabled state are configuration data rather than Java constants. The seeded selectors accept official article URLs under each source host and ignore pagination controls, navigation links, and non-recruitment columns.

The implementation must not rely on search-engine result pages or third-party aggregators. A source may be disabled by configuration if its official entry URL changes.

## 3. Scope Boundaries

### Included

- Static HTML list and detail pages fetched over HTTPS.
- HTML, XHTML, PDF, XLS, and XLSX acquisition.
- Attachment discovery from announcement detail pages.
- Existing deterministic Excel import for official spreadsheets.
- Existing Phase 2 HTML/PDF extraction and Review Queue.
- Manual run and scheduled run.
- PostgreSQL-backed source definitions, run history, document state, and change feed.
- Conditional HTTP requests, bounded retries, host throttling, payload limits, and distributed run locking.
- Metrics and REST endpoints for operations and change consumption.

### Excluded

- Playwright and login-required sources.
- Automatic CAPTCHA solving or bypassing anti-bot controls.
- District, university, hospital, and SOE source expansion beyond the two initial sources.
- Email, WeChat, SMS, or mobile push delivery. Phase 3A exposes a durable change feed for those later adapters.
- Full-text search, recommendation scoring, application automation, and frontend dashboards.
- LLM-based discovery of links. Discovery is deterministic.

## 4. Architecture

The new acquisition subsystem follows the existing domain/application/infrastructure/web boundaries.

```text
Scheduler or Manual API
        |
        v
AcquisitionOrchestrator
        |
        +--> SourceRepository
        +--> DistributedSourceLock
        +--> StaticHtmlSourceDiscoverer
        +--> HttpDocumentFetcher
        +--> DocumentStateRepository
        +--> AcquiredDocumentRouter
                 |
                 +--> Phase 2 HTML/PDF ExtractionService
                 +--> OfficialExcelImportService
        |
        +--> CrawlRunRepository
        +--> AcquisitionChangeRepository
```

Discovery, transport, state comparison, and content routing are separate ports. Adding Playwright later supplies another discoverer/fetcher without changing scheduling or delta semantics.

## 5. Domain Model

### 5.1 RecruitmentSource

`RecruitmentSource` describes one independently scheduled official list page.

Required fields:

- `id: UUID`
- `code: String`, globally unique and immutable
- `name: String`
- `baseUri: URI`
- `entryUri: URI`
- `sourceType: OFFICIAL_GOVERNMENT | OFFICIAL_ORGANIZATION | OFFICIAL_SOE | OFFICIAL_UNIVERSITY | AGGREGATOR | UNKNOWN`
- `region: String`
- `crawlMode: STATIC_HTML | PLAYWRIGHT`
- `enabled: boolean`
- `cronExpression: String`
- `timeZone: String`, seeded as `Asia/Shanghai`
- `minimumRequestInterval: Duration`, seeded as one second
- `configuration: JSON object`
- `lastSuccessAt`, `lastFailureAt`, `nextDueAt: Instant?`
- `consecutiveFailureCount: int`
- `createdAt`, `updatedAt: Instant`

Phase 3A rejects enabled sources whose mode is not `STATIC_HTML`.

`configuration` has a validated schema:

```json
{
  "articleUrlRegex": "^https://host/art/[0-9]{4}/[0-9]+/[0-9]+/art_[A-Za-z0-9_]+\\.html$",
  "linkSelector": "a[href]",
  "attachmentSelector": "a[href]",
  "titleIncludeRegex": "招聘|招考|选聘|引进",
  "titleExcludeRegex": "拟聘|公示|成绩|体检|递补",
  "maxListPages": 2
}
```

The concrete host is source-specific. Regex matching occurs after URI resolution and canonicalization.

### 5.2 SourceCrawlRun

One attempt to poll one source:

- `id: UUID`
- `sourceId: UUID`
- `trigger: SCHEDULED | MANUAL`
- `status: RUNNING | SUCCEEDED | PARTIALLY_SUCCEEDED | FAILED | SKIPPED_LOCKED`
- `startedAt`, `completedAt: Instant?`
- `discoveredCount`, `fetchedCount`, `unchangedCount`, `addedCount`, `updatedCount`, `deactivatedCount`, `failedCount: int`
- `errorCode`, `errorMessage: String?`

A failed document does not roll back successful documents from the same run. The run is `PARTIALLY_SUCCEEDED` when at least one document succeeded and at least one failed.

### 5.3 AcquiredDocument

This is the durable state of one canonical official detail page or attachment:

- `id: UUID`
- `sourceId: UUID`
- `canonicalUri: URI`, unique per source
- `parentDocumentId: UUID?` for attachments
- `kind: ANNOUNCEMENT | ATTACHMENT`
- `mediaType: String`
- `contentFingerprint: lowercase SHA-256 hex`
- `etag`, `lastModified: String?`
- `storageUri: URI`
- `state: ACTIVE | DEACTIVATED`
- `firstSeenAt`, `lastSeenAt`, `lastChangedAt: Instant`
- `consecutiveGoneCount: int`
- `lastHttpStatus: int`
- `lastProcessedFingerprint: String?`
- `version: long`

`canonicalUri` normalization lowercases scheme and host, removes fragments and default ports, removes known tracking parameters, sorts remaining query parameters, and normalizes an empty path to `/`. It does not remove business query parameters.

### 5.4 AcquisitionChange

A durable, append-only change feed record:

- `id: UUID`
- `runId`, `sourceId`, `documentId: UUID`
- `changeType: ADDED | UPDATED | DEACTIVATED`
- `previousFingerprint: String?`
- `currentFingerprint: String?`
- `canonicalUri: URI`
- `jobDeltaSummary: JSON object`
- `occurredAt: Instant`

`jobDeltaSummary` records counts and stable job keys produced by the downstream job-upsert result. It never stores invented job data.

The unique constraint `(document_id, change_type, current_fingerprint)` prevents duplicate feed entries. For deactivation, `current_fingerprint` is represented by a fixed sentinel digest so the database constraint remains non-null and deterministic.

## 6. Acquisition Flow

1. The scheduler selects enabled sources whose `nextDueAt <= now`.
2. The orchestrator creates a `RUNNING` crawl run.
3. It obtains a PostgreSQL advisory lock keyed by source code. Failure to acquire within two seconds produces `SKIPPED_LOCKED` and performs no network calls.
4. The discoverer fetches the configured list pages and extracts matching canonical detail links.
5. Every detail page is fetched with stored `ETag` and `Last-Modified` validators when available.
6. HTTP `304` updates `lastSeenAt` and counts `UNCHANGED`; it creates no `AcquisitionChange` and does not rerun extraction.
7. HTTP `200` content is size-checked, fingerprinted, and persisted in the existing filesystem artifact store before processing.
8. A new canonical URI creates an `ADDED` record. A known URI with a different fingerprint creates an `UPDATED` record. The same fingerprint is `UNCHANGED`.
9. New or updated HTML/PDF is routed to the Phase 2 `ExtractionService`. XLS/XLSX is routed to `OfficialExcelImportService`. Unsupported media is retained as evidence metadata, marked as a document failure, and does not crash the source run.
10. Attachment links on HTML detail pages are resolved, canonicalized, and fetched through the same document-state algorithm.
11. The run counters and source health fields are updated in a final transaction.

The acquisition change and document state update are committed atomically. Downstream extraction uses its existing fingerprint lock and transaction boundaries. If extraction fails, the document retains its fetched fingerprint but `lastProcessedFingerprint` is unchanged; the next run retries processing even when the HTTP content itself is unchanged.

## 7. Incremental and Deactivation Semantics

### Added

A canonical document has never been stored for the source. The document is persisted and processed exactly once for its initial fingerprint.

### Updated

The canonical URI exists and the fetched SHA-256 differs from its current fingerprint. The previous fingerprint remains in the change record and the new version is processed.

### Unchanged

The server returns `304`, or it returns `200` with the same SHA-256. `lastSeenAt` advances, but no change-feed item or extraction run is created unless `lastProcessedFingerprint` differs from `contentFingerprint` because an earlier processing attempt failed.

### Deactivated

Listing-page disappearance alone never deactivates a document or job because announcements can fall off pagination.

A document is deactivated only when one of these conditions holds:

1. its canonical detail URI returns `404` or `410` on two consecutive completed source runs at least six hours apart; or
2. an official detail page explicitly identifies the original announcement as cancelled or withdrawn and provides a link or unambiguous title reference to it; or
3. a successful Phase 2 extraction for the same source URL declares `completeSnapshot=true`, in which case missing stable job keys are handled by the existing job-upsert service.

Timeouts, TLS failures, `429`, and `5xx` responses never imply deactivation. Passing an application deadline also does not imply that the source or historical job record is deactivated.

When a deactivated URI later returns `200`, it becomes active and emits `UPDATED`, preserving its original `firstSeenAt`.

## 8. HTTP Safety and Politeness

- Java HTTP client follows at most five redirects.
- Connect timeout is five seconds; request timeout is twenty seconds.
- Retry at most two times for connect failures, `429`, and `5xx`, using bounded backoff and `Retry-After` when present.
- Do not retry other `4xx` responses.
- Maximum response body is 25 MiB.
- At most two requests per host run concurrently.
- Requests to the same host begin at least one second apart.
- The user agent is `CareerOS/0.3 (+private research; contact configured by CAREER_OS_HTTP_CONTACT)`.
- Credentials and cookies are not sent.
- Redirects to a host outside the source allowlist are rejected.
- Content type is checked using headers, filename, and magic bytes; an HTML error page named `.xlsx` must not reach the workbook parser.

## 9. Scheduling

The application enables Spring scheduling. A lightweight dispatcher runs once per minute and claims due sources. Each source stores its own cron expression and `Asia/Shanghai` time zone. The two seeds run daily at staggered times:

- Zhejiang: `0 10 8 * * *`
- Hangzhou: `0 20 8 * * *`

`nextDueAt` is computed after every terminal run, including failures. There is no unbounded immediate retry loop. Manual runs do not change the scheduled cadence.

## 10. API

All endpoints use the existing `/api` prefix.

### Source operations

- `GET /api/acquisition/sources`
- `POST /api/acquisition/sources/{sourceId}/runs` returns `202 Accepted` and a run identifier.
- `GET /api/acquisition/runs/{runId}`
- `GET /api/acquisition/runs?sourceId=&status=&from=&to=&page=&size=`

Concurrent manual triggers for a locked source return a run in `SKIPPED_LOCKED`; they do not return a generic server error.

### Change feed

- `GET /api/acquisition/changes?after=<instant>&sourceId=&types=ADDED,UPDATED,DEACTIVATED&page=&size=`

Ordering is `(occurredAt ASC, id ASC)`. The response includes `nextCursor`, encoded from the last `(occurredAt,id)` pair, so notification adapters can resume without missing records that share a timestamp. Page size defaults to 50 and is capped at 200.

## 11. Persistence and Migrations

Flyway migration `V6__incremental_acquisition.sql` creates:

- `recruitment_source`
- `source_crawl_run`
- `acquired_document`
- `acquisition_change`

It also seeds the two source records with deterministic UUIDs and configuration JSON. Database constraints enforce enum values, non-negative counters, fingerprint length, source-code uniqueness, canonical-URI uniqueness per source, and parent attachment ownership.

No existing migration is edited. Upgrade from V5 must preserve every existing source artifact, extraction run, recruitment event, and job posting.

## 12. Observability

Micrometer metrics:

- `careeros.acquisition.runs{source,status}`
- `careeros.acquisition.documents{source,result}`
- `careeros.acquisition.fetch.duration{source}`
- `careeros.acquisition.processing.failures{source,media_type}`
- `careeros.acquisition.lock.skipped{source}`

Logs include `runId`, `sourceCode`, and canonical URI, but never document bodies, credentials, or user-provided model keys.

## 13. Error Handling

- One document failure is isolated and recorded; remaining documents continue.
- A list-page failure makes the run `FAILED` because discovery did not complete.
- Detail or attachment failures after successful discovery make the run `PARTIALLY_SUCCEEDED`.
- Database or artifact-store failure aborts only the current document transaction.
- A failed document is retried on the next source run.
- Repeated source failures increment `consecutiveFailureCount`; a later successful run resets it to zero.
- Phase 3A does not automatically disable a failing source.

## 14. Testing Strategy

All production behavior follows red-green-refactor TDD.

### Unit tests

- URI canonicalization and host allowlisting.
- Source configuration validation.
- Link inclusion and exclusion.
- Conditional request construction.
- Media type and magic-byte detection.
- Added, updated, unchanged, processing-retry, deactivated, and reactivated state transitions.
- Run status aggregation and cursor ordering.

### Integration tests

- Flyway V5-to-V6 upgrade and seed verification with PostgreSQL Testcontainers.
- Distributed lock behavior across two application contexts.
- Unique document/change constraints under concurrent triggers.
- Filesystem artifact persistence and rollback behavior.
- HTTP tests use an in-process stub server with captured official-page fixtures; the normal test suite does not require public internet access.
- End-to-end fixture flow: list page -> detail page -> attachment -> Phase 2 extraction/import -> job delta -> acquisition change.

### Live smoke test

An opt-in test profile fetches one page from each configured official source with strict request limits. It verifies reachability, selectors, matching links, media types, and artifact hashing. It does not write to the developer's normal database and is not part of `mvn test`.

## 15. Acceptance Criteria

1. A clean PostgreSQL database migrates through V6 and contains exactly two enabled seed sources.
2. A first fixture run creates document and change records and routes supported content into the existing Phase 2 services.
3. Repeating the identical run creates no `ADDED`, `UPDATED`, or duplicate job records.
4. Changing one fixture body creates exactly one `UPDATED` document change and only the corresponding downstream job deltas.
5. Two qualifying `404`/`410` runs produce exactly one `DEACTIVATED` change; list disappearance, timeout, `429`, and `5xx` do not.
6. A processing failure is retried on the next unchanged fetch and cannot duplicate a change-feed entry.
7. Concurrent source triggers across two instances execute network acquisition once.
8. Failure of one detail page does not prevent another detail page from being persisted and processed.
9. Change-feed cursor pagination returns every record exactly once when consumed sequentially.
10. Existing Phase 0-2 tests remain green, the full Maven package succeeds, and both opt-in official-source smoke checks pass or produce a documented source-compatibility failure without corrupting stored state.

## 16. Delivery Boundary

Passing Phase 3A means the two-source acquisition backend is technically accepted. It does not mean the full Career OS master specification is complete. The next separately designed increment may add district/university/hospital sources and a notification adapter using the durable change-feed cursor.
