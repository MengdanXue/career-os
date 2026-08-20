CREATE TABLE job_admission (
    job_posting_id UUID PRIMARY KEY REFERENCES job_posting(id) ON DELETE CASCADE,
    data_quality_status TEXT NOT NULL CHECK (
        data_quality_status IN ('RAW', 'PARSED', 'NORMALIZED', 'REVIEW_REQUIRED', 'VERIFIED', 'REJECTED', 'FAILED')
    ),
    target_scope_status TEXT NOT NULL CHECK (
        target_scope_status IN ('INCLUDED', 'EXCLUDED', 'NEEDS_REVIEW')
    ),
    reason_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    evaluator_version TEXT NOT NULL,
    assessed_at TIMESTAMPTZ NOT NULL,
    human_verified BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_job_admission_quality_scope
    ON job_admission(data_quality_status, target_scope_status);
CREATE INDEX idx_job_admission_scope
    ON job_admission(target_scope_status);

INSERT INTO job_admission (
    job_posting_id, data_quality_status, target_scope_status, reason_codes,
    evaluator_version, assessed_at, human_verified
)
SELECT id, 'RAW', 'NEEDS_REVIEW', '["LEGACY_UNVERIFIED"]'::jsonb,
       'admission-v1', now(), FALSE
FROM job_posting
ON CONFLICT (job_posting_id) DO NOTHING;

CREATE OR REPLACE FUNCTION initialize_job_admission()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    EXECUTE format(
        'INSERT INTO %I.job_admission '
        || '(job_posting_id, data_quality_status, target_scope_status, reason_codes, evaluator_version, assessed_at, human_verified) '
        || 'VALUES ($1, ''RAW'', ''NEEDS_REVIEW'', ''["NOT_CLASSIFIED"]''::jsonb, ''admission-v1'', now(), FALSE) '
        || 'ON CONFLICT (job_posting_id) DO NOTHING',
        TG_TABLE_SCHEMA
    ) USING NEW.id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_job_admission_after_insert
AFTER INSERT ON job_posting
FOR EACH ROW
EXECUTE FUNCTION initialize_job_admission();

CREATE OR REPLACE FUNCTION reset_job_admission_on_content_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    EXECUTE format(
        'INSERT INTO %I.job_admission '
        || '(job_posting_id, data_quality_status, target_scope_status, reason_codes, evaluator_version, assessed_at, human_verified) '
        || 'VALUES ($1, ''RAW'', ''NEEDS_REVIEW'', ''["CONTENT_CHANGED"]''::jsonb, ''admission-v1'', now(), FALSE) '
        || 'ON CONFLICT (job_posting_id) DO UPDATE SET '
        || 'data_quality_status = EXCLUDED.data_quality_status, '
        || 'target_scope_status = EXCLUDED.target_scope_status, '
        || 'reason_codes = EXCLUDED.reason_codes, '
        || 'evaluator_version = EXCLUDED.evaluator_version, '
        || 'assessed_at = EXCLUDED.assessed_at, '
        || 'human_verified = EXCLUDED.human_verified',
        TG_TABLE_SCHEMA
    ) USING NEW.id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_job_admission_content_change
AFTER UPDATE OF content_fingerprint ON job_posting
FOR EACH ROW
WHEN (OLD.content_fingerprint IS DISTINCT FROM NEW.content_fingerprint)
EXECUTE FUNCTION reset_job_admission_on_content_change();
