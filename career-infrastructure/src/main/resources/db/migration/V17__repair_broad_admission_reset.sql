-- The frozen V14 migration normalized every automated row. Restore rows that
-- have no official-workbook provenance to RAW; their prior terminal status is
-- not guessed. Rows reclassified after V14 have another evaluator version and
-- are deliberately left untouched.
UPDATE job_admission admission
SET data_quality_status = 'RAW'
WHERE admission.human_verified = FALSE
  AND admission.data_quality_status = 'NORMALIZED'
  AND admission.evaluator_version = 'admission-v3-evidence-reset'
  AND NOT EXISTS (
      SELECT 1
      FROM job_posting job
      JOIN LATERAL jsonb_array_elements_text(job.evidence_ids) evidence_id ON TRUE
      JOIN evidence item ON item.id = evidence_id::uuid
      WHERE job.id = admission.job_posting_id
        AND item.evidence_type = 'OFFICIAL_ATTACHMENT'
  );

