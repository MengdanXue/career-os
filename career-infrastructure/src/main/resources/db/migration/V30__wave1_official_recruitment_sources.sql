UPDATE recruitment_source
SET configuration = configuration || jsonb_build_object('adapterType', 'JCMS_LISTING'),
    updated_at = now()
WHERE code IN ('ZJ_HRSS_INSTITUTION', 'HZ_HRSS_INSTITUTION');

INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES
(
    '01992f09-0000-7000-8000-000000000303',
    'HDU_RECRUITMENT',
    '杭州电子科技大学招聘',
    'https://renshi.hdu.edu.cn/',
    'https://renshi.hdu.edu.cn/rczp/list.htm',
    'OFFICIAL_UNIVERSITY', '杭州', 'STATIC_HTML', TRUE,
    '0 30 8 * * *', 'Asia/Shanghai', 1500,
    '{
      "adapterType":"STATIC_HTML",
      "historicalPaginationMode":"STATIC_PAGE_SUFFIX",
      "historicalMaxPages":50,
      "historicalYears":[2024,2025,2026],
      "articleUrlRegex":"^https://renshi\\.hdu\\.edu\\.cn/[0-9]{4}/[0-9]{4}/c[0-9]+a[0-9]+/page\\.htm$",
      "linkSelector":"a[href]",
      "attachmentSelector":"a[href]",
      "titleIncludeRegex":"招聘|招考|选聘|引进|劳务派遣",
      "titleExcludeRegex":"拟聘|公示|成绩|体检|递补"
    }'::jsonb,
    now()
),
(
    '01992f09-0000-7000-8000-000000000304',
    'ZJGSU_RECRUITMENT',
    '浙江工商大学招聘',
    'https://talents.zjgsu.edu.cn/',
    'https://talents.zjgsu.edu.cn/rczp/list.htm',
    'OFFICIAL_UNIVERSITY', '杭州', 'STATIC_HTML', TRUE,
    '0 40 8 * * *', 'Asia/Shanghai', 1500,
    '{
      "adapterType":"STATIC_HTML",
      "historicalPaginationMode":"STATIC_PAGE_SUFFIX",
      "historicalMaxPages":50,
      "historicalYears":[2024,2025,2026],
      "articleUrlRegex":"^https://talents\\.zjgsu\\.edu\\.cn/[0-9]{4}/[0-9]{4}/c[0-9]+a[0-9]+/page\\.htm$",
      "linkSelector":"a[href]",
      "attachmentSelector":"a[href]",
      "titleIncludeRegex":"招聘|招考|选聘|引进|人才",
      "titleExcludeRegex":"拟聘|公示|成绩|体检|递补"
    }'::jsonb,
    now()
)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    base_uri = EXCLUDED.base_uri,
    entry_uri = EXCLUDED.entry_uri,
    source_type = EXCLUDED.source_type,
    region = EXCLUDED.region,
    crawl_mode = EXCLUDED.crawl_mode,
    enabled = EXCLUDED.enabled,
    cron_expression = EXCLUDED.cron_expression,
    time_zone = EXCLUDED.time_zone,
    minimum_request_interval_ms = EXCLUDED.minimum_request_interval_ms,
    configuration = EXCLUDED.configuration,
    next_due_at = EXCLUDED.next_due_at,
    updated_at = now();

INSERT INTO source_year_coverage (
    source_id, recruitment_year, status, discovered_count, fetched_count,
    parsed_count, target_job_count, updated_at
)
SELECT source.id, year.value, 'NOT_DISCOVERED', 0, 0, 0, 0, now()
FROM recruitment_source source
CROSS JOIN generate_series(2024, 2026) AS year(value)
WHERE source.code IN ('HDU_RECRUITMENT', 'ZJGSU_RECRUITMENT')
ON CONFLICT (source_id, recruitment_year) DO NOTHING;

UPDATE target_source_catalog target
SET recruitment_source_id = source.id,
    connection_status = 'PARTIAL',
    official_root_url = source.base_uri,
    updated_at = now()
FROM recruitment_source source
WHERE target.code = source.code
  AND target.code IN ('HDU_RECRUITMENT', 'ZJGSU_RECRUITMENT');

INSERT INTO source_onboarding_checkpoint (
    source_id, checkpoint, status, evidence, verified_at
)
SELECT id, 'REGISTERED', 'VERIFIED',
       '官方来源身份、HTTPS 根域名和计划任务已登记', now()
FROM recruitment_source
WHERE code IN ('HDU_RECRUITMENT', 'ZJGSU_RECRUITMENT')
ON CONFLICT (source_id, checkpoint) DO UPDATE SET
    status = EXCLUDED.status,
    evidence = EXCLUDED.evidence,
    verified_at = EXCLUDED.verified_at;
