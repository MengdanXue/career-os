-- Early development builds of V13 created job field evidence but did not yet
-- create the corresponding announcement-event evidence table. Keep this
-- migration idempotent so an existing data-bearing database is repaired while
-- a fresh database remains unchanged.
CREATE TABLE IF NOT EXISTS recruitment_event_field_evidence (
    id UUID PRIMARY KEY,
    recruitment_event_id UUID NOT NULL REFERENCES recruitment_event(id) ON DELETE CASCADE,
    field_name TEXT NOT NULL CHECK (length(trim(field_name)) > 0),
    fact_status TEXT NOT NULL CHECK (
        fact_status IN ('EXPLICIT', 'NOT_REQUIRED', 'DERIVED', 'UNKNOWN', 'CONFLICT')
    ),
    evidence_fragment_id UUID REFERENCES evidence_fragment(id) ON DELETE RESTRICT,
    raw_value TEXT,
    extractor_version TEXT NOT NULL CHECK (length(trim(extractor_version)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_recruitment_event_field_evidence UNIQUE NULLS NOT DISTINCT
        (recruitment_event_id, field_name, evidence_fragment_id),
    CHECK (fact_status = 'UNKNOWN' OR evidence_fragment_id IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_recruitment_event_field_evidence_event
    ON recruitment_event_field_evidence(recruitment_event_id, field_name);

-- Re-apply the final automated-admission reset. This is intentionally
-- restricted to non-human VERIFIED rows and is safe when V14 already ran.
UPDATE job_admission
SET data_quality_status = 'NORMALIZED',
    reason_codes = CASE
        WHEN reason_codes @> '["MISSING_FIELD_EVIDENCE"]'::jsonb THEN reason_codes
        ELSE reason_codes || '["MISSING_FIELD_EVIDENCE"]'::jsonb
    END,
    evaluator_version = 'admission-v3-evidence-reset',
    assessed_at = now()
WHERE human_verified = FALSE
  AND data_quality_status = 'VERIFIED';
