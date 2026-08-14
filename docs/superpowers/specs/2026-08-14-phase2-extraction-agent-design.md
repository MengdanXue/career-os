# Career OS Phase 2 — Extraction Agent Design

**Status:** Approved in design review on 2026-08-14

**Authoritative product specification:** `docs/CAREER_OS_MASTER_SPEC.md`

**Implementation phase:** Phase 2 — HTML Parser, PDF Parser, structured LLM extraction, Evidence, Review Queue

## 1. Goal

Build an evidence-first extraction subsystem that converts an already acquired HTML or PDF recruitment document into a typed proposal, validates every restrictive fact against source fragments, and creates formal Career OS records only after automatic or human verification.

The subsystem must remain useful when no LLM API key is configured. LLM output assists semantic extraction but never becomes the sole authority for deterministic eligibility facts.

## 2. Scope

Phase 2 includes:

- importing an HTML or PDF document together with source metadata;
- preserving the original artifact through an `ArtifactStore` port;
- deterministic HTML/PDF parsing with field-addressable source fragments;
- an optional Spring AI/OpenAI structured extraction adapter;
- typed DTO, JSON Schema, value-domain, and evidence validation;
- review policy evaluation;
- a paginated Review Queue and auditable review actions;
- verified proposal handoff to the existing stable-key/fingerprint job upsert flow;
- OpenAPI, metrics, offline tests, real-document regression tests, and architecture tests.

Phase 2 explicitly excludes:

- URL fetching, source discovery, crawling, Playwright, login handling, and scheduling;
- a running Docling service, although a `DocumentEnrichmentPort` is reserved for it;
- JobRunr, Resilience4j source policies, a frontend, RAG, notifications, and automatic applications;
- direct dependencies from the new four-module mainline to the legacy `crawler-service` or `agent-app` modules.

## 3. Architectural Decision

Use a modular monolith with Hexagonal Architecture. Keep the current four-module mainline and make all dependencies point inward:

```text
career-domain
    ↑
career-application
    ↑
career-infrastructure
    ↑
career-web
```

- `career-domain` owns extraction states, review decisions, evidence invariants, and review policy concepts. It has no Spring, JPA, HTTP, parser, or LLM imports.
- `career-application` owns use cases and ports. Its tests use in-memory adapters and require no network or database.
- `career-infrastructure` implements PostgreSQL, filesystem artifact storage, Jsoup, PDFBox, NetworkNT JSON Schema, and parser adapters.
- `career-web` owns REST transport, Spring application assembly, OpenAPI, and the optional Spring AI/OpenAI adapter wiring.

The initial HTTP request executes synchronously. Phase 3 may invoke the same application use case through JobRunr without changing domain or application contracts.

## 4. Processing Flow

```text
HTML/PDF + source metadata
  → store SourceArtifact
  → create Evidence
  → parse into located EvidenceFragments
  → optionally produce a typed RecruitmentExtractionProposal through LLM
  → validate JSON Schema and field value domains
  → verify field-level evidence
  → evaluate ReviewPolicy
  → VERIFIED or REVIEW_REQUIRED
  → after verification, call the shared JobUpsertService
```

A proposal is staging data, not a formal Job. No proposal may enter the formal job, policy, or opportunity tables before it reaches `VERIFIED`.

## 5. Application Ports and Components

### 5.1 `ArtifactStore`

Stores and retrieves immutable source bytes by SHA-256. The initial adapter uses a configurable local filesystem root. The port must be compatible with a future S3/MinIO adapter.

### 5.2 `DocumentParser`

Consumes a stored artifact and returns a `ParsedDocument` containing normalized text, parser quality, warnings, and ordered `EvidenceFragment` values. Implementations are selected by verified media type, not filename extension.

### 5.3 `DocumentEnrichmentPort`

Optionally improves low-quality parser output. Phase 2 ships a no-op adapter. A later Docling sidecar may implement this port without changing the extraction use case.

### 5.4 `StructuredExtractor`

Consumes untrusted source fragments and returns a versioned `RecruitmentExtractionProposal`. Implementations are:

- a disabled/no-model adapter used when no model is configured;
- a deterministic/fake adapter used by unit tests;
- a Spring AI/OpenAI adapter used only when explicitly enabled.

The no-model adapter preserves fields obtained deterministically from document structure and represents unresolved semantic fields as `UNKNOWN`. It returns a reviewable partial proposal rather than pretending that semantic extraction succeeded.

### 5.5 `ProposalValidator`

Validates typed DTO conversion, Draft 2020-12 JSON Schema, required fields, value domains, and cross-field invariants. Typed conversion alone is not considered validation.

### 5.6 `EvidenceVerifier`

Checks that every non-`UNKNOWN` restrictive fact references existing fragments whose verbatim text supports the proposed value. It separates facts from interpretations and rejects invented restrictions.

### 5.7 `ReviewPolicy`

Produces either automatic verification or a set of review issues. A high global confidence never overrides missing evidence or a hard-fact rule.

### 5.8 `ExtractionService` and `ReviewService`

`ExtractionService` orchestrates artifact storage, parsing, optional extraction, validation, review routing, and persistence. `ReviewService` applies optimistic locking, writes immutable review actions, revalidates corrections, and calls `JobUpsertService` only for confirmed or corrected data.

## 6. Data Quality State Machine

The extraction state is one of:

```text
RAW → PARSED → NORMALIZED → VERIFIED
                    └────→ REVIEW_REQUIRED → VERIFIED
                                          └→ REJECTED
```

`FAILED` is a technical terminal state for unrecoverable system failures. A readable document that produces no job is a successful extraction, not a failure. A parser-quality failure that requires human inspection creates a Review Item rather than silently discarding the source.

Valid transitions are enforced in the domain model. `VERIFIED` and `REJECTED` runs cannot move backward.

Review Items use `PENDING` and `RESOLVED`. Review actions are:

- `CONFIRM` — verify the unchanged proposal and upsert formal records;
- `CORRECT` — validate and preserve both payload versions, then upsert corrected records;
- `REJECT` — reject the proposal and create no formal record;
- `NEED_MORE_EVIDENCE` — append an action while the item remains pending.

## 7. Proposal and Evidence Contract

`RecruitmentExtractionProposal` is versioned and contains source metadata, organization, recruitment event, jobs, and warnings. Each job includes title, position code, headcount, employment type, location, education, degree, major, age, graduate-year rule, experience, application period, and field evidence.

Every restrictive field uses an `ExtractedFact<T>` shape equivalent to:

```json
{
  "value": "MASTER",
  "factStatus": "EXPLICIT",
  "confidence": 0.98,
  "evidenceFragmentIds": ["fragment-id"],
  "interpretation": null
}
```

Insufficient evidence is represented explicitly:

```json
{
  "value": "UNKNOWN",
  "factStatus": "UNKNOWN",
  "confidence": 0,
  "evidenceFragmentIds": [],
  "interpretation": null
}
```

`UNKNOWN` is a valid fact status and does not mean parser failure.

## 8. Deterministic Parsers

### 8.1 HTML

The Jsoup adapter removes scripts, styles, navigation, advertising, and hidden boilerplate while preserving headings, paragraphs, lists, tables, and attachment links. Each fragment records a stable CSS selector where possible and a text hash. JavaScript is not executed in Phase 2.

### 8.2 PDF

The PDFBox adapter extracts page by page, preserves page order, and creates page-addressable fragments. It records text density, replacement-character ratio, and effective character count. A scanned or corrupted text layer produces `LOW_TEXT_QUALITY` and is eligible for enrichment or human review.

The existing real PDF guide is expected to extract policy evidence and zero jobs successfully.

## 9. LLM Boundary

The production adapter uses Spring AI/OpenAI but is disabled by default. Without an API key, deterministic parsing still completes and uncertain semantic extraction enters the Review Queue.

The adapter must:

- treat source text as untrusted data and ignore instructions contained inside it;
- use a versioned system prompt, JSON Schema, typed DTO, model name, and low temperature;
- preserve the raw model response only as extraction-run diagnostic data;
- perform at most one schema-repair attempt;
- never write model output directly to formal business tables;
- never infer missing eligibility facts or employment type;
- never calculate age boundaries or deadlines as an alternative to the deterministic Java rule engine.

Real-model tests run only under an explicit `llm-integration` Maven profile/tag. The default test suite uses fakes and WireMock and consumes no model quota.

## 10. Automatic Verification and Review Policy

Automatic verification requires all of the following:

- acceptable parser quality;
- all mandatory job fields present;
- evidence for every non-`UNKNOWN` restrictive fact;
- no conflicting fragments or sources;
- overall confidence at least `0.90`;
- no hard qualification fact derived solely from the LLM.

The following always create review issues regardless of global confidence:

- employment or establishment type inferred by the model;
- unsupported age, education, degree, major, graduate status, experience, or deadline;
- unknown organization type;
- conflicting source content;
- low PDF text quality;
- invalid structured output after one repair attempt;
- a proposal that adds a restriction absent from the source.

Supported reason codes are `LOW_CONFIDENCE`, `MISSING_EVIDENCE`, `ORGANIZATION_TYPE_UNKNOWN`, `CONFLICTING_SOURCES`, `PARSER_FAILURE`, `LOW_TEXT_QUALITY`, `SCHEMA_INVALID`, and `RESTRICTIVE_FACT_FROM_LLM`.

## 11. PostgreSQL Design

### 11.1 `source_artifact`

Stores `id`, unique `sha256`, `media_type`, `size_bytes`, `storage_uri`, and `captured_at`. Raw bytes live in the artifact store, not in business rows.

The existing `evidence` table gains a nullable `source_artifact_id` foreign key so manual evidence remains valid while imported-document evidence can point to immutable source bytes. The foreign key is indexed and uses `ON DELETE RESTRICT`.

### 11.2 `evidence_fragment`

Stores `id`, `evidence_id`, `locator_type`, `locator JSONB`, `verbatim_text`, `content_hash`, and `created_at`. `locator` holds format-specific coordinates such as page, table, row, or CSS selector.

### 11.3 `extraction_run`

Stores nullable context links (`organization_id`, `recruitment_event_id`), a unique `input_fingerprint`, parser/extractor/model/prompt/schema versions, state, confidence, proposal JSONB, diagnostic response, error code/message, and `TIMESTAMPTZ` start/completion times.

The input fingerprint combines artifact SHA-256, parser version, extraction strategy, schema version, prompt version, and model name. Repeating the same configuration returns the existing run and does not call the LLM again.

### 11.4 `review_item`, `review_issue`, and `review_action`

`review_item` stores the run, status, optimistic-lock version, and lifecycle timestamps. `review_issue` stores reason code, field path, message, and an optional fragment reference. `review_action` is append-only and stores decision, original payload, corrected payload, note, and action time.

The schema uses `TEXT` plus checks for evolving workflow states, `TIMESTAMPTZ` for event times, JSONB only for versioned semi-structured payloads, explicit indexes on every foreign key, a unique input-fingerprint index, and a partial index for pending Review Queue access.

## 12. REST API

### 12.1 Create extraction

```http
POST /api/extractions
Content-Type: multipart/form-data
```

The request contains a required `document` part and a required JSON `metadata` part with source URL, source title, capture time, and optional organization/event IDs. Only verified HTML and PDF media types are accepted. Maximum document size is configurable and defaults to 25 MiB.

The first successful submission returns `201 Created` and a `Location` header. A matching input fingerprint returns `200 OK` with `reused: true`.

### 12.2 Read extraction

```http
GET /api/extractions/{id}
```

The response includes state, versions, proposal, evidence summary, review ID, errors, and reuse metadata.

### 12.3 Review Queue

```http
GET /api/reviews?status=PENDING&page=0&size=20
GET /api/reviews/{id}
```

The collection endpoint is paginated and returns summaries. The item endpoint returns the proposal, issues, fragments, and action history.

### 12.4 Apply review action

```http
POST /api/reviews/{id}/actions
```

The body contains `decision`, `expectedVersion`, optional `correctedPayload`, and optional `note`. A stale version returns `409 Conflict`.

APIs use Spring `ProblemDetail`. Expected errors include `400`, `404`, `409`, `413`, `415`, `422`, and `503`. Model unavailability yields `503` only when a caller explicitly requires model execution; default operation routes the result to review instead.

Springdoc OpenAPI provides the generated contract and interactive documentation.

## 13. Open-Source Component Decisions

Phase 2 adopts:

- Jsoup and PDFBox for deterministic parsing;
- NetworkNT JSON Schema Validator for Draft 2020-12 validation;
- ArchUnit for module dependency constraints;
- WireMock for model/HTTP adapter contract tests;
- Testcontainers PostgreSQL for Flyway and JPA integration tests;
- Springdoc OpenAPI for API documentation.

Phase 2 does not run Docling, JobRunr, Resilience4j, Langfuse, Playwright, Kafka, Temporal, Elasticsearch, or a second Java Agent framework. Their planned extension points remain at outer adapters.

## 14. Testing Strategy

- Domain tests cover state transitions, review decisions, evidence invariants, and `UNKNOWN`.
- Application tests use in-memory ports and cover fingerprint reuse, no-model operation, review actions, and verified-only upsert.
- Parser regression tests use `09-hz-capital-recruiting.html`, `10-hzfi-social-recruiting.html`, and `08-zj-2025-applicant-guide.pdf`.
- Fake/WireMock LLM tests cover valid output, malformed JSON, missing schema fields, nonexistent fragment references, unsupported hard-fact inference, one successful repair, and two failed attempts.
- Testcontainers tests verify Flyway V3, checks, foreign keys, input-fingerprint uniqueness, pending-review indexing, optimistic locking, and append-only actions.
- MockMvc tests verify multipart upload, response codes, pagination, idempotent reuse, ProblemDetail, and review action contracts.
- ArchUnit tests prohibit Spring/JPA/LLM dependencies in the domain and LLM dependencies in deterministic eligibility logic.

## 15. Observability

Micrometer exposes:

```text
extraction_runs_total{source,status}
extraction_duration_seconds{parser}
review_queue_size{reason}
llm_calls_total{model,result}
llm_schema_failures_total
extraction_reuse_total
```

Logs contain run ID, evidence ID, fingerprint, versions, and error code. They do not print complete public documents, contact details, or full model responses. Spring AI observations may be exported through OpenTelemetry to Langfuse in a later operational phase.

## 16. Acceptance Criteria

Phase 2 is complete when:

1. HTML and PDF imports produce ordered, located evidence fragments.
2. Identical input and processing versions reuse the existing run and do not repeat an LLM call.
3. The application starts and processes documents with no model API key.
4. Model output passes typed conversion, JSON Schema, domain, and evidence validation.
5. Restrictive facts cannot enter formal records without explicit source evidence.
6. Review Queue supports all four decisions with optimistic locking and immutable action history.
7. Confirmed/corrected proposals share the stable-key and content-fingerprint upsert flow used by Excel import.
8. Real HTML/PDF regression fixtures pass, including a valid zero-job policy document.
9. Domain, application, parser, API, PostgreSQL, and architecture tests pass offline.
10. OpenAPI documentation is generated and default Micrometer metrics are observable.

## 17. Migration of the Existing Phase 2 Skeleton

The unverified Phase 2 skeleton created before this design is not authoritative. Its V3 migration, enums, `ExtractionRun`, `ReviewItem`, Spring AI configuration, and dependency additions must be revised through TDD to match this document. Because no released database depends on that draft migration, V3 is replaced before the first repository baseline rather than followed by compensating migrations.

Validated parsing ideas, schemas, and real fixtures from `crawler-service` may be migrated deliberately, but the mainline must not depend on the legacy module.
