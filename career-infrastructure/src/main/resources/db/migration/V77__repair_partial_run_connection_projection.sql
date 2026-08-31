WITH latest_run AS (
    SELECT DISTINCT ON (run.source_id)
        run.source_id,
        run.status,
        run.fetched_count + run.unchanged_count + run.added_count + run.updated_count AS successful_items
    FROM source_crawl_run run
    ORDER BY run.source_id, run.started_at DESC, run.id DESC
)
UPDATE target_source_catalog target
SET connection_status = 'PARTIAL',
    updated_at = now()
FROM recruitment_source source
JOIN latest_run latest ON latest.source_id = source.id
WHERE target.recruitment_source_id = source.id
  AND target.connection_status = 'FAILED'
  AND source.enabled
  AND latest.status = 'PARTIALLY_SUCCEEDED'
  AND latest.successful_items > 0;
