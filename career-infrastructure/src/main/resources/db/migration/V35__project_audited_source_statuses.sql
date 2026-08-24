UPDATE target_source_catalog target
SET connection_status = CASE
        WHEN NOT source.enabled THEN 'NOT_CONNECTED'
        WHEN source.consecutive_failure_count >= 3 THEN 'FAILED'
        WHEN (
            SELECT count(DISTINCT coverage.recruitment_year)
            FROM source_year_coverage coverage
            WHERE coverage.source_id = source.id
              AND coverage.recruitment_year BETWEEN 2024 AND 2026
              AND coverage.status IN ('COMPLETE', 'NO_TARGET_RECORDS')
        ) = 3
        AND EXISTS (
            SELECT 1 FROM source_crawl_run run
            WHERE run.source_id = source.id
              AND run.status = 'SUCCEEDED'
              AND run.completed_at >= now() - interval '48 hours'
        )
        AND EXISTS (
            SELECT 1 FROM source_onboarding_checkpoint checkpoint
            WHERE checkpoint.source_id = source.id
              AND checkpoint.checkpoint = 'CONTRACT_VERIFIED'
              AND checkpoint.status = 'VERIFIED'
        )
        AND EXISTS (
            SELECT 1
            FROM source_onboarding_checkpoint backfill
            JOIN source_onboarding_checkpoint incremental
              ON incremental.source_id = backfill.source_id
             AND incremental.checkpoint = 'INCREMENTAL_VERIFIED'
             AND incremental.status = 'VERIFIED'
             AND incremental.verified_at >= backfill.verified_at
            WHERE backfill.source_id = source.id
              AND backfill.checkpoint = 'BACKFILL_COMPLETE'
              AND backfill.status = 'VERIFIED'
        ) THEN 'CONNECTED'
        ELSE 'PARTIAL'
    END,
    updated_at = now()
FROM recruitment_source source
WHERE target.recruitment_source_id = source.id;
