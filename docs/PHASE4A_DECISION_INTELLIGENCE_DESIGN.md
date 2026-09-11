# Career OS Phase 4A — Auditable Decision Intelligence Agent

**Status:** Approved  
**Date:** 2026-08-20  
**Scope:** Hangzhou/Zhejiang semi-public-sector technical career decisions

## 1. Objective

Turn the existing evidence-backed, incremental recruitment database into a real Agent application that can answer questions such as:

> Which active Hangzhou semi-public-sector technology jobs fit me, how stable are they, and what evidence supports the recommendation?

The Agent must orchestrate deterministic tools. It must never invent eligibility, employment identity, stability evidence, scores, or admission probabilities.

## 2. Product boundary

Phase 4A delivers:

- deterministic hard-eligibility gating;
- multidimensional fit assessment;
- evidence-aware stability assessment;
- Hangzhou semi-public-sector opportunity tiers;
- persisted, versioned decision snapshots;
- ranked decision APIs;
- an eligibility/decision explanation Agent with deterministic fallback;
- automatic invalidation and reassessment when the candidate profile or job content changes.

Phase 4A does not deliver:

- autonomous job application;
- admission-probability prediction;
- historical competition forecasting;
- resume or interview agents;
- unrestricted multi-agent autonomy;
- vector search or pgvector before a measured semantic-search need exists.

## 3. Alternatives considered

### 3.1 Recommended: decision-intelligence slice in the modular mainline

Add pure decision models to `career-domain`, orchestration and ports to `career-application`, PostgreSQL adapters to `career-infrastructure`, and REST/Agent adapters to `career-web`.

This reuses the current PostgreSQL source of truth and preserves Clean Architecture dependency direction.

### 3.2 Full intelligence platform in one phase

Policy reasoning, semantic matching, historical grouping, forecasting, resume generation, and interview preparation would all be implemented together. This has high delivery and truthfulness risk because the current data does not support several of those conclusions.

Rejected for Phase 4A.

### 3.3 Revive the legacy `agent-app`

The legacy module uses a file-based workflow and depends on the excluded crawler module. It would create a second source of truth and duplicate the current acquisition pipeline.

Rejected. It remains outside the Maven reactor.

## 4. Architectural shape

```text
User / API client
       |
       v
Decision Agent Orchestrator (career-web adapter)
       |
       +--> search active jobs
       +--> evaluate hard eligibility
       +--> assess fit
       +--> assess stability
       +--> rank opportunities
       +--> load supporting evidence
       |
       v
Decision Intelligence Service (career-application)
       |
       v
Pure evaluators and value objects (career-domain)
       |
       v
Repository ports -> PostgreSQL adapters (career-infrastructure)
```

Dependency rules:

- `career-domain` has no Spring, JPA, HTTP, or model-provider dependency.
- `career-application` depends only on domain objects and ports.
- `career-infrastructure` implements persistence ports and maps JPA records to domain records.
- `career-web` handles HTTP, Spring wiring, and optional model-backed tool orchestration.
- Model output cannot mutate deterministic assessment fields.

## 5. Domain model

### 5.1 Candidate decision profile

Extend `CandidateProfile` with the evidence needed by the Master Spec:

- `skills`;
- `researchKeywords`;
- `targetJobFamilies`;
- `preferredOrganizationTypes`.

The existing `profileVersion` remains part of the assessment input key. Missing attributes remain unknown and reduce assessment coverage; they are not inferred by the model.

### 5.2 Assessment dimensions

Every scored dimension records:

- dimension name;
- achieved points;
- maximum points;
- fact status: `EXPLICIT`, `INTERPRETED`, or `UNKNOWN`;
- reason code and human-readable explanation;
- supporting evidence identifiers.

Unknown dimensions earn zero points and lower coverage. This is intentionally conservative and prevents sparse profiles from receiving inflated scores.

### 5.3 Fit assessment

The score follows the Master Spec and totals 100 points:

| Dimension | Maximum |
|---|---:|
| Major fit | 25 |
| Skill fit | 20 |
| Engineering/experience fit | 20 |
| Research fit | 10 |
| Professional-title fit | 10 |
| Location and employment preference fit | 15 |

Matching behavior is deterministic in Phase 4A:

- normalized exact token overlap for majors, skills, research keywords, and titles;
- experience thresholds use numeric comparison;
- location uses normalized containment;
- employment and organization preferences use enum equality;
- unknown job requirements do not become positive evidence.

Fuzzy semantic matching is deferred until a reviewed semantic mapping or embedding corpus exists.

### 5.4 Stability assessment

Stability dimensions follow the Master Spec:

- employment security;
- funding stability;
- organization stability;
- policy stability;
- business volatility;
- layoff risk;
- contract risk.

Phase 4A can deterministically assess employment security and contract risk from explicit employment type. Other dimensions require evidence-backed stability facts. Without such facts they remain unknown.

An organization type alone is not sufficient evidence for funding, policy continuity, or layoff risk.

### 5.5 Opportunity tier

`OpportunityTier` values:

- `T1`: explicit establishment or equivalent stable identity with supporting evidence;
- `T2`: formal non-dispatch role in a stable semi-public organization, with enough evidence to distinguish it from outsourcing;
- `T3`: personnel agency, labor dispatch, project-based work, outsourcing, short-term contract, or unresolved identity;
- `EXCLUDED`: deterministic eligibility status is `INELIGIBLE`. Missing or conflicting
  evidence is never grounds for exclusion — `NEEDS_CONFIRMATION`, `CONFLICTING_EVIDENCE`
  and `CONDITIONAL` all stay in the list as items a person has to resolve.

An unknown employment identity can never receive T1.

### 5.6 Decision assessment

A decision assessment references:

- candidate and job identifiers;
- eligibility, fit, and stability assessments;
- opportunity tier;
- recommendation status;
- assessment coverage;
- evaluator version;
- candidate profile version;
- job content fingerprint;
- assessment timestamp.

Phase 4A ranks eligible opportunities by tier, fit score, stability score, coverage, deadline, and stable job identifier. It does not label this ordering as admission probability.

## 6. Persistence design

Assessment snapshots are append-only by input version. Repeating the same input resolves to the existing snapshot.

Core tables:

- `fit_assessment`;
- `stability_assessment`;
- `decision_assessment`;
- `assessment_dimension`;
- `organization_stability_fact`.

Key design rules:

- UUIDs remain consistent with the existing aggregate identifiers.
- Timestamps use `TIMESTAMPTZ`.
- business statuses use `TEXT` plus `CHECK` constraints so values can evolve through migrations;
- foreign-key columns receive explicit indexes;
- a unique input key covers candidate, job, profile version, job fingerprint, and evaluator version;
- dimensions are normalized rows rather than an opaque JSON document;
- optional evidence links use a junction table rather than duplicated arrays;
- no table partitioning is introduced at this scale.

The current `opportunity.match_score` remains temporarily readable for compatibility but stops being the decision source of truth. A later migration may remove it after all consumers use decision assessments.

## 7. Application services and ports

`DecisionIntelligenceService`:

1. load candidate, job, event, organization, and evidence;
2. run or reuse deterministic eligibility;
3. compute or reuse fit and stability snapshots;
4. assign the opportunity tier;
5. persist an idempotent decision snapshot;
6. return an explanation-ready projection.

`DecisionRankingService`:

- evaluates active jobs for a candidate;
- excludes hard-ineligible jobs from recommendations while retaining their audit records;
- supports tier, location, job family, and deadline filters;
- returns bounded, stably ordered pages.

`DecisionExplanationService`:

- builds a deterministic Chinese explanation from rule results and evidence;
- exposes a tool-safe response for optional model-backed phrasing;
- never accepts revised scores or statuses from the model.

Required ports include repositories for assessments and organization stability facts, a current-job query port, and an evidence lookup port.

## 8. API design

Resources are versioned under `/api/v1`:

- `PUT /api/v1/candidates/{candidateId}/decision-profile` updates decision attributes and advances `profileVersion`;
- `POST /api/v1/candidates/{candidateId}/job-decisions/{jobId}` creates or reuses an assessment snapshot;
- `GET /api/v1/candidates/{candidateId}/job-decisions/{jobId}` returns the current assessment;
- `GET /api/v1/candidates/{candidateId}/job-decisions` returns a paginated ranking;
- `POST /api/v1/candidates/{candidateId}/agent-queries` answers a bounded career-decision question.

Error behavior:

- missing candidate or job: `404`;
- malformed input: `400` with a stable problem code;
- assessment unavailable due to internal failure: `500` with no fabricated result;
- insufficient evidence: `200` with unknown dimensions, coverage, and warnings;
- optional model failure: `200` with deterministic fallback explanation and a fallback indicator.

Collection responses include explicit pagination metadata. OpenAPI documents score semantics and states that success ranking is not a probability.

## 9. Agent behavior

The Spring AI adapter exposes bounded tools:

- `search_active_jobs`;
- `get_job_evidence`;
- `evaluate_eligibility`;
- `assess_fit`;
- `assess_stability`;
- `rank_opportunities`;
- `explain_eligibility`.

The model may interpret user intent, select filters, call tools, and phrase a response. The server returns the structured assessment next to any natural-language answer, making contradictions detectable.

The default implementation works without an API key. Model configuration enhances phrasing and intent extraction only.

## 10. Incremental refresh

The decision input key includes the candidate profile version and the stable job content fingerprint produced by Phase 3.

- unchanged job + unchanged profile + unchanged evaluator: reuse snapshot;
- changed job fingerprint: recompute affected candidate/job assessments;
- changed candidate profile version: recompute that candidate's current-job assessments;
- downlined job: retain its historical assessment but omit it from active rankings;
- evaluator version change: create new snapshots without rewriting history.

The first implementation performs bounded synchronous refresh on assessment/ranking requests. Background refresh may be added only after measured runtime shows it is needed.

## 11. Testing and acceptance

Implementation follows red-green-refactor TDD.

Required tests:

- hard-ineligible jobs never appear as recommendations;
- unknown data cannot create positive points or T1 classification;
- explicit establishment evidence produces T1 when eligibility passes;
- labor dispatch and project-based work cannot exceed T3;
- dimension scores and coverage use hand-derived fixtures;
- identical input versions are idempotent;
- changed profile version or job fingerprint creates a new snapshot;
- model failure returns deterministic output;
- model text cannot change structured status or score;
- ranking order is stable and paginated;
- migration constraints and indexes are verified;
- real Hangzhou/Zhejiang golden fixtures cover T1, T2, T3, excluded, and insufficient-evidence cases;
- all reactor tests and package verification pass.

## 12. Delivery sequence

1. Add failing domain tests and implement assessment value objects/evaluators.
2. Add failing application tests and implement decision orchestration/ranking ports.
3. Add migration tests and PostgreSQL adapters.
4. Add failing API tests and implement REST projections and error handling.
5. Add Agent fallback/tool-boundary tests and implement the web adapter.
6. Run full verification, review the complete diff, fix all critical/important findings, commit, and push the private branch.

