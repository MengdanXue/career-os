ALTER TABLE recruitment_event
    ADD COLUMN application_starts_at TIMESTAMPTZ,
    ADD COLUMN application_ends_at TIMESTAMPTZ,
    ADD COLUMN age_reference_date DATE,
    ADD COLUMN registration_url TEXT,
    ADD COLUMN qualification_review_ends_on TIMESTAMPTZ,
    ADD COLUMN payment_ends_on TIMESTAMPTZ,
    ADD COLUMN admission_ticket_starts_on DATE,
    ADD COLUMN admission_ticket_ends_on DATE,
    ADD COLUMN written_exam_on DATE,
    ADD COLUMN written_exam_subjects JSONB NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(written_exam_subjects) = 'array'),
    ADD COLUMN graduate_rule TEXT,
    ADD COLUMN overseas_degree_rule TEXT,
    ADD COLUMN experience_evidence_rule TEXT,
    ADD COLUMN employment_statement TEXT,
    ADD COLUMN interview_rule TEXT,
    ADD COLUMN legacy_workbook_snapshot BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE recruitment_event event
SET legacy_workbook_snapshot = TRUE
WHERE EXISTS (
    SELECT 1 FROM job_posting job
    WHERE job.recruitment_event_id = event.id
      AND job.stable_job_key IS NOT NULL
);

ALTER TABLE job_posting
    ADD COLUMN supervising_department TEXT,
    ADD COLUMN job_category TEXT,
    ADD COLUMN job_grade TEXT,
    ADD COLUMN education_requirement_text TEXT,
    ADD COLUMN degree_requirement TEXT,
    ADD COLUMN major_requirement_text TEXT,
    ADD COLUMN age_requirement_text TEXT,
    ADD COLUMN gender_requirement TEXT,
    ADD COLUMN candidate_scope TEXT,
    ADD COLUMN other_requirements TEXT,
    ADD COLUMN original_requirement_text TEXT,
    ADD COLUMN interview_ratio TEXT,
    ADD COLUMN professional_test_required BOOLEAN,
    ADD COLUMN contact_phone TEXT;

ALTER TABLE acquired_document
    ADD COLUMN last_processor_version TEXT;

ALTER TABLE evidence_fragment
    DROP CONSTRAINT evidence_fragment_locator_type_check;

ALTER TABLE evidence_fragment
    ADD CONSTRAINT evidence_fragment_locator_type_check
    CHECK (locator_type IN ('HTML', 'PDF', 'SPREADSHEET'));

CREATE TABLE job_field_evidence (
    id UUID PRIMARY KEY,
    job_posting_id UUID NOT NULL REFERENCES job_posting(id) ON DELETE CASCADE,
    field_name TEXT NOT NULL CHECK (length(trim(field_name)) > 0),
    fact_status TEXT NOT NULL CHECK (
        fact_status IN ('EXPLICIT', 'NOT_REQUIRED', 'DERIVED', 'UNKNOWN', 'CONFLICT')
    ),
    evidence_fragment_id UUID REFERENCES evidence_fragment(id) ON DELETE RESTRICT,
    raw_value TEXT,
    normalized_value TEXT,
    extractor_version TEXT NOT NULL CHECK (length(trim(extractor_version)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_job_field_evidence UNIQUE NULLS NOT DISTINCT
        (job_posting_id, field_name, evidence_fragment_id),
    CHECK (fact_status = 'UNKNOWN' OR evidence_fragment_id IS NOT NULL)
);

CREATE INDEX idx_job_field_evidence_job
    ON job_field_evidence(job_posting_id, field_name);

CREATE INDEX idx_job_field_evidence_fragment
    ON job_field_evidence(evidence_fragment_id)
    WHERE evidence_fragment_id IS NOT NULL;
