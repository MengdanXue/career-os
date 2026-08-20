# Candidate Confirmed Facts Design

## Outcome

Career OS must distinguish a stored candidate value from a candidate-confirmed decision fact. Seeded, imported, or edited values cannot satisfy qualification or add positive fit points until the candidate confirms the current value.

## Domain model

`CandidateFactKey` identifies each decision input. `CandidateFactConfirmation` stores the status (`UNCONFIRMED`, `CONFIRMED`, or `UNKNOWN`), a SHA-256 fingerprint of the canonical current value, source, and timestamps. A confirmation is usable only when its stored fingerprint equals the current field fingerprint.

The first slice covers birth date, education, majors, graduation year, experience years, professional titles, skills, research keywords, locations, accepted employment types, target job families, and preferred organization types. The six hard-qualification keys are birth date, education, majors, graduation year, experience years, and professional titles.

## Application behavior

Candidate writes are coordinated by `CandidateProfileService`, not by the HTTP controller. The service owns profile versions. On an edit it preserves a confirmation only when the field fingerprint is unchanged; changed fields become `UNCONFIRMED`. A confirmation command records the current fingerprints and issues a new profile version. Both operations are transactional at the HTTP boundary.

`DecisionIntelligenceService` loads the candidate facts with the candidate profile. `EligibilityEvaluator` returns `UNCERTAIN` for a candidate-dependent rule whose fact is not currently confirmed. `FitEvaluator` gives no positive points for an unconfirmed candidate input. The server-generated profile version keeps previous decision snapshots auditable while preventing them from being returned as current.

## Persistence

Migration V12 creates `candidate_fact_confirmation` with composite primary key `(candidate_profile_id, fact_key)`, a cascading foreign key to `candidate_profile`, checked text statuses, a 64-character fingerprint, source, and `TIMESTAMPTZ` timestamps. Existing seeded values have no rows and therefore resolve as unconfirmed or unknown until the candidate acts.

## API and interface

- `GET /api/v1/candidates/{id}/facts` returns the current server-owned fact states and readiness counts.
- `POST /api/v1/candidates/{id}/facts/confirm` confirms the requested current fields and returns the new profile plus readiness.
- `PUT /api/v1/candidates/{id}` ignores any client profile version and returns a server-versioned profile.

The profile page loads server fact state, removes the local-storage confirmation authority, exposes professional titles, and presents one “confirm” action. After a successful confirmation, another browser sees the same state. Editing a confirmed value and saving without a successful reconfirmation leaves the changed fact unconfirmed.

## Failure handling

Missing candidates return 404. Unknown fact keys or invalid states return 400. Confirmation never accepts a client fingerprint. If profile persistence and fact persistence cannot both complete, the transaction rolls back. A failed confirmation remains visibly retryable and cannot silently mark the profile ready.

## Acceptance

1. An unconfirmed birth date makes a constrained age rule uncertain.
2. Confirming the current birth date enables deterministic age evaluation.
3. Editing that birth date invalidates only its confirmation.
4. Empty confirmed professional titles mean a known absence; unconfirmed titles mean uncertainty.
5. Confirmation is visible across browser sessions and profile versions are server generated.
6. Unconfirmed skills, research, and preferences add no fit points.
