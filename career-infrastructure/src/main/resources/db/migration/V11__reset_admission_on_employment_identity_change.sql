CREATE OR REPLACE FUNCTION reset_job_admission_on_employment_identity_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE reset_reason JSONB;
BEGIN
    reset_reason := CASE
        WHEN NEW.employment_type = 'UNKNOWN'
            THEN '["EMPLOYMENT_IDENTITY_UNKNOWN"]'::jsonb
        ELSE '["CONTENT_CHANGED"]'::jsonb
    END;

    EXECUTE format(
        'UPDATE %I.job_admission SET '
        || 'data_quality_status = ''REVIEW_REQUIRED'', '
        || 'target_scope_status = ''NEEDS_REVIEW'', '
        || 'reason_codes = $2, '
        || 'evaluator_version = ''admission-v2'', '
        || 'assessed_at = now(), '
        || 'human_verified = FALSE '
        || 'WHERE job_posting_id = $1',
        TG_TABLE_SCHEMA
    ) USING NEW.id, reset_reason;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_job_admission_employment_identity_change
AFTER UPDATE OF employment_type ON job_posting
FOR EACH ROW
WHEN (OLD.employment_type IS DISTINCT FROM NEW.employment_type)
EXECUTE FUNCTION reset_job_admission_on_employment_identity_change();
