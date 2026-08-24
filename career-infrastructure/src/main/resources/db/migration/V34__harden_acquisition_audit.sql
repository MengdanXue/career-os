ALTER TABLE artifact_import_failure
    ALTER COLUMN document_id DROP NOT NULL,
    ALTER COLUMN safe_message TYPE VARCHAR(500);

ALTER TABLE artifact_import_failure
    ADD CONSTRAINT ck_artifact_import_failure_safe_message_length
        CHECK (char_length(safe_message) BETWEEN 1 AND 500),
    ADD CONSTRAINT ck_artifact_import_failure_row_document
        CHECK (stage <> 'ROW_PARSE_FAILED' OR document_id IS NOT NULL);

CREATE INDEX idx_artifact_import_failure_source_occurred
    ON artifact_import_failure(source_id, occurred_at DESC, id DESC);

CREATE INDEX idx_artifact_import_failure_document_stage
    ON artifact_import_failure(document_id, stage)
    WHERE document_id IS NOT NULL;

WITH audited AS (
    SELECT source.id AS source_id,
           max(coverage.completed_at) AS backfill_at,
           (SELECT max(run.completed_at)
            FROM source_crawl_run run
            WHERE run.source_id = source.id
              AND run.status = 'SUCCEEDED') AS latest_success_at
    FROM recruitment_source source
    JOIN source_year_coverage coverage ON coverage.source_id = source.id
    WHERE coverage.recruitment_year BETWEEN 2024 AND 2026
      AND coverage.status IN ('COMPLETE', 'NO_TARGET_RECORDS')
      AND coverage.completed_at IS NOT NULL
    GROUP BY source.id
    HAVING count(DISTINCT coverage.recruitment_year) = 3
), evidence AS (
    SELECT source_id, 'LIVE_SMOKE_VERIFIED'::varchar AS checkpoint,
           latest_success_at AS verified_at,
           'Migrated from persisted successful source run'::text AS evidence
    FROM audited WHERE latest_success_at IS NOT NULL
    UNION ALL
    SELECT source_id, 'BACKFILL_COMPLETE', backfill_at,
           'Migrated from conclusive 2024-2026 source_year_coverage'
    FROM audited
)
INSERT INTO source_onboarding_checkpoint(source_id, checkpoint, status, evidence, verified_at)
SELECT source_id, checkpoint, 'VERIFIED', evidence, verified_at
FROM evidence
ON CONFLICT (source_id, checkpoint) DO UPDATE SET
    status = EXCLUDED.status,
    evidence = EXCLUDED.evidence,
    verified_at = EXCLUDED.verified_at;
