INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES (
    '01992f09-0000-7000-8000-000000000305',
    'HZ_FIRST_HOSPITAL', '杭州市第一人民医院招聘',
    'https://www.hz-hospital.com/', 'https://www.hz-hospital.com/',
    'OFFICIAL_ORGANIZATION', '杭州', 'STATIC_HTML', TRUE,
    '0 50 8 * * *', 'Asia/Shanghai', 1500,
    '{
      "adapterType":"HOSPITAL_OFFICIAL_EVIDENCE",
      "historicalPaginationMode":"FIXED_HTTPS_EVIDENCE",
      "historicalYears":[2024,2025,2026],
      "historicalEvidenceByYear":{
        "2024":[
          "https://zp.hz-hospital.com/index/index/announcement_desc/id/176.html",
          "https://zp.hz-hospital.com/index/index/announcement_desc/id/181.html",
          "https://zp.hz-hospital.com/index/index/announcement_desc/id/188.html",
          "https://zp.hz-hospital.com/index/index/announcement_desc/id/207.html",
          "https://zp.hz-hospital.com/index/index/announcement_desc/id/212.html",
          "https://zp.hz-hospital.com/index/index/announcement_desc/id/231.html"
        ],
        "2025":["https://www.hz-hospital.com/content/details/id/224426?cid=68"],
        "2026":[
          "https://www.hz-hospital.com/content/details/id/227185?cid=68",
          "https://www.hz-hospital.com/content/details/id/228230?cid=68"
        ]
      },
      "allowedHosts":["zp.hz-hospital.com","www.hz-hospital.com","wsjkw.hangzhou.gov.cn","hrss.hangzhou.gov.cn"],
      "articleUrlRegex":"^https://www\\.hz-hospital\\.com/content/details/id/[0-9]+(?:\\?cid=[0-9]+)?$",
      "linkSelector":"a[href]",
      "attachmentSelector":"a[href]",
      "titleIncludeRegex":"招聘|招贤|人才",
      "titleExcludeRegex":"拟聘|公示|成绩|体检|递补"
    }'::jsonb,
    now()
)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name, base_uri = EXCLUDED.base_uri, entry_uri = EXCLUDED.entry_uri,
    source_type = EXCLUDED.source_type, region = EXCLUDED.region, crawl_mode = EXCLUDED.crawl_mode,
    enabled = EXCLUDED.enabled, cron_expression = EXCLUDED.cron_expression,
    time_zone = EXCLUDED.time_zone,
    minimum_request_interval_ms = EXCLUDED.minimum_request_interval_ms,
    configuration = EXCLUDED.configuration, next_due_at = EXCLUDED.next_due_at, updated_at = now();

INSERT INTO source_year_coverage (
    source_id, recruitment_year, status, discovered_count, fetched_count,
    parsed_count, target_job_count, updated_at
)
SELECT id, year.value, 'NOT_DISCOVERED', 0, 0, 0, 0, now()
FROM recruitment_source
CROSS JOIN generate_series(2024, 2026) AS year(value)
WHERE code = 'HZ_FIRST_HOSPITAL'
ON CONFLICT (source_id, recruitment_year) DO NOTHING;

UPDATE target_source_catalog target
SET recruitment_source_id = source.id,
    connection_status = 'PARTIAL',
    official_root_url = source.base_uri,
    updated_at = now()
FROM recruitment_source source
WHERE target.code = 'HZ_FIRST_HOSPITAL' AND source.code = target.code;

INSERT INTO source_onboarding_checkpoint(source_id, checkpoint, status, evidence, verified_at)
SELECT id, 'REGISTERED', 'VERIFIED',
       '官方医院主站与历史 HTTPS 公告域名已登记；HTTP 报名系统仅保留为正文证据', now()
FROM recruitment_source WHERE code = 'HZ_FIRST_HOSPITAL'
ON CONFLICT (source_id, checkpoint) DO UPDATE SET
    status = EXCLUDED.status, evidence = EXCLUDED.evidence, verified_at = EXCLUDED.verified_at;
