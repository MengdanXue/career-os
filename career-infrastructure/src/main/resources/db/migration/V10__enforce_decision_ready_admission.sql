UPDATE job_admission admission
SET data_quality_status = 'REVIEW_REQUIRED',
    target_scope_status = 'NEEDS_REVIEW',
    reason_codes = '["EMPLOYMENT_IDENTITY_UNKNOWN"]'::jsonb,
    evaluator_version = 'admission-v2',
    assessed_at = now(),
    human_verified = FALSE
FROM job_posting job
WHERE admission.job_posting_id = job.id
  AND admission.data_quality_status = 'VERIFIED'
  AND admission.target_scope_status = 'INCLUDED'
  AND job.employment_type = 'UNKNOWN';

CREATE OR REPLACE FUNCTION enforce_decision_ready_admission()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE employment_identity TEXT;
BEGIN
    IF NEW.data_quality_status = 'VERIFIED' AND NEW.target_scope_status = 'INCLUDED' THEN
        EXECUTE format(
            'SELECT employment_type FROM %I.job_posting WHERE id = $1',
            TG_TABLE_SCHEMA
        ) INTO employment_identity USING NEW.job_posting_id;
        IF employment_identity IS NULL OR employment_identity = 'UNKNOWN' THEN
            RAISE EXCEPTION 'Cannot admit job % without verified employment identity', NEW.job_posting_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_job_admission_decision_ready
BEFORE INSERT OR UPDATE OF data_quality_status, target_scope_status ON job_admission
FOR EACH ROW
EXECUTE FUNCTION enforce_decision_ready_admission();
