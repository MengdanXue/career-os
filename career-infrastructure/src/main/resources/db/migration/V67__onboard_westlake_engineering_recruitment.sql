INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES (
    '01992f09-0000-7000-8000-000000000429',
    'WESTLAKE_RESEARCH', '西湖大学科研技术岗位',
    'https://www.westlake.edu.cn/',
    'https://engineering.westlake.edu.cn/Recuitment/',
    'OFFICIAL_UNIVERSITY', '浙江杭州', 'STATIC_HTML', TRUE,
    '0 50 10 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "allowedHosts":["www.westlake.edu.cn","engineering.westlake.edu.cn"],
      "allowedPathPrefixes":["/","/Recuitment/"],
      "listingEntries":[{
        "code":"engineering-research-and-technical",
        "entryUri":"https://engineering.westlake.edu.cn/Recuitment/",
        "role":"PRIMARY","mode":"JS_OBJECT_ARRAY",
        "recruitmentYears":[2024,2025,2026,2027],
        "completenessRequired":false,
        "embeddedArrayMarker":"talentList",
        "jsonUrlField":"url","jsonTitleField":"title",
        "jsonPublishedDateField":"date",
        "allowedHosts":["engineering.westlake.edu.cn"],
        "allowedPathPrefixes":["/Recuitment/"],
        "articleUrlRegex":"^https://engineering\\.westlake\\.edu\\.cn/Recuitment/[0-9]{6}/t[0-9]{8}_[0-9]+\\.shtml$",
        "titleIncludeRegex":"招聘|工程师|技术员|科研助理|研究助理",
        "titleExcludeRegex":"博士后|教授|教师|学术人才|行政助理|综合行政|招生|实习|访问学生"
      }]
    }'::jsonb, now()
)
ON CONFLICT (code) DO UPDATE SET
    name=EXCLUDED.name, base_uri=EXCLUDED.base_uri, entry_uri=EXCLUDED.entry_uri,
    source_type=EXCLUDED.source_type, region=EXCLUDED.region, crawl_mode=EXCLUDED.crawl_mode,
    enabled=EXCLUDED.enabled, cron_expression=EXCLUDED.cron_expression,
    time_zone=EXCLUDED.time_zone, minimum_request_interval_ms=EXCLUDED.minimum_request_interval_ms,
    configuration=EXCLUDED.configuration, next_due_at=EXCLUDED.next_due_at,
    last_failure_at=NULL, consecutive_failure_count=0, updated_at=now();

INSERT INTO source_year_coverage (source_id, recruitment_year, status, discovered_count,
    fetched_count, parsed_count, target_job_count, updated_at)
SELECT source.id, year.value, 'NOT_DISCOVERED', 0, 0, 0, 0, now()
FROM recruitment_source source CROSS JOIN generate_series(2024, 2027) AS year(value)
WHERE source.code='WESTLAKE_RESEARCH'
ON CONFLICT (source_id, recruitment_year) DO NOTHING;

UPDATE target_source_catalog target
SET recruitment_source_id=source.id,
    connection_status=CASE WHEN target.connection_status='CONNECTED' THEN 'CONNECTED' ELSE 'PARTIAL' END,
    official_root_url=source.base_uri, updated_at=now()
FROM recruitment_source source
WHERE target.code='WESTLAKE_RESEARCH' AND source.code=target.code;

INSERT INTO source_onboarding_checkpoint (source_id, checkpoint, status, evidence, verified_at)
SELECT source.id, checkpoint.value,
       CASE WHEN checkpoint.value='REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
       CASE checkpoint.value
         WHEN 'REGISTERED' THEN '西湖大学工学院官方招聘页内嵌完整岗位数组已登记，稳定键使用官方详情 URL'
         WHEN 'CONTRACT_VERIFIED' THEN '等待机器复核 talentList 对象数组、发布日期、详情 URL 和排除规则'
         WHEN 'LIVE_SMOKE_VERIFIED' THEN '等待受控实网保存计算机、AI、软件和工程技术相关招聘正文'
         WHEN 'BACKFILL_COMPLETE' THEN '工学院列表公开 2024-2026 历史；校级行政 Moka 档案不在此入口，来源保持 PARTIAL'
         ELSE '等待第二次无重复增量运行验证稳定 URL 和正文/列表元数据指纹'
       END, now()
FROM recruitment_source source
CROSS JOIN (VALUES ('REGISTERED'),('CONTRACT_VERIFIED'),('LIVE_SMOKE_VERIFIED'),
    ('BACKFILL_COMPLETE'),('INCREMENTAL_VERIFIED')) AS checkpoint(value)
WHERE source.code='WESTLAKE_RESEARCH'
ON CONFLICT (source_id, checkpoint) DO NOTHING;
