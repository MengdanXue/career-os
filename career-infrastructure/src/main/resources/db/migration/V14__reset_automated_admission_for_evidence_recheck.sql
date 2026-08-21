UPDATE job_admission
SET data_quality_status = 'NORMALIZED',
    reason_codes = CASE
        WHEN reason_codes @> '["MISSING_FIELD_EVIDENCE"]'::jsonb THEN reason_codes
        ELSE reason_codes || '["MISSING_FIELD_EVIDENCE"]'::jsonb
    END,
    evaluator_version = 'admission-v3-evidence-reset',
    assessed_at = now()
WHERE human_verified = FALSE;

UPDATE recruitment_event event
SET legacy_workbook_snapshot = FALSE
WHERE legacy_workbook_snapshot = TRUE
  AND NOT EXISTS (
      SELECT 1 FROM job_posting job
      WHERE job.recruitment_event_id = event.id
        AND job.active = TRUE
  );
