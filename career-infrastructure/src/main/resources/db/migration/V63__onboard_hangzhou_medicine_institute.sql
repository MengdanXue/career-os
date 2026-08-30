INSERT INTO target_source_catalog (
    code, name, route_code, organization_type, region, authority_level,
    official_root_url, connection_status, recruitment_source_id, enabled,
    scope_level, scope_code, priority_tier, coverage_role, updated_at
) VALUES (
    'CAS_HANGZHOU_MEDICINE', '中国科学院杭州医学研究所招聘',
    'RESEARCH_SUPPORT', 'RESEARCH_INSTITUTE', '杭州', 'OFFICIAL_ORGANIZATION',
    'https://him.cas.cn/', 'PARTIAL', NULL, TRUE,
    'ORGANIZATION', 'CAS_HANGZHOU_MEDICINE', 'P0', 'PRIMARY', now()
)
ON CONFLICT (code) DO UPDATE SET
    name=EXCLUDED.name, route_code=EXCLUDED.route_code,
    organization_type=EXCLUDED.organization_type, region=EXCLUDED.region,
    authority_level=EXCLUDED.authority_level, official_root_url=EXCLUDED.official_root_url,
    enabled=EXCLUDED.enabled, scope_level=EXCLUDED.scope_level,
    scope_code=EXCLUDED.scope_code, priority_tier=EXCLUDED.priority_tier,
    coverage_role=EXCLUDED.coverage_role, updated_at=now();

INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES (
    '01992f09-0000-7000-8000-000000000426',
    'CAS_HANGZHOU_MEDICINE', '中国科学院杭州医学研究所招聘',
    'https://him.cas.cn/', 'https://him.cas.cn/rczp/zpgg/',
    'OFFICIAL_ORGANIZATION', '浙江杭州', 'STATIC_HTML', TRUE,
    '0 5 10 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "allowedHosts":["him.cas.cn","www.him.cas.cn"],
      "allowedPathPrefixes":["/rczp/zpgg/"],
      "attachmentSelector":"a[href]",
      "scriptAttachmentVariable":"appLinkStr",
      "listingEntries":[{
        "code":"engineering-and-research-support",
        "entryUri":"https://him.cas.cn/rczp/zpgg/",
        "role":"PRIMARY","mode":"LINKED_PAGE",
        "recruitmentYears":[2024,2025,2026,2027],
        "knownArchiveGapYears":[2024],"completenessRequired":true,
        "historicalMaxPages":1,"incrementalListingMaxPages":1,
        "nextPageSelector":"a.__career_os_no_next__[href]",
        "allowedHosts":["him.cas.cn","www.him.cas.cn"],
        "allowedPathPrefixes":["/rczp/zpgg/"],
        "listingItemSelector":".zhaopin-list-div",
        "itemLinkSelector":".zhaopin-list-left > a[href]",
        "itemTitleSelector":"h1","itemPublishedDateSelector":"h1 span",
        "linkSelector":".zhaopin-list-left > a[href]",
        "articleUrlRegex":"^https://(?:www\\.)?him\\.cas\\.cn/rczp/zpgg/[0-9]{6}/t[0-9]{8}_[0-9]+\\.html$",
        "titleIncludeRegex":"招聘|工程技术|工程师|科研支撑|拟聘用|考试|考核|资格|面试|体检|考察|公示|递补",
        "titleExcludeRegex":"劳务派遣|编外劳务派遣|科研助理招聘启事|博士后招聘公告"
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
WHERE source.code='CAS_HANGZHOU_MEDICINE'
ON CONFLICT (source_id, recruitment_year) DO NOTHING;

UPDATE target_source_catalog target
SET recruitment_source_id=source.id,
    connection_status=CASE
        WHEN target.connection_status = 'CONNECTED' THEN 'CONNECTED'
        ELSE 'PARTIAL'
    END,
    official_root_url=source.base_uri, updated_at=now()
FROM recruitment_source source
WHERE target.code='CAS_HANGZHOU_MEDICINE' AND source.code=target.code;

INSERT INTO source_onboarding_checkpoint (source_id, checkpoint, status, evidence, verified_at)
SELECT source.id, checkpoint.value,
       CASE WHEN checkpoint.value='REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
       CASE checkpoint.value
         WHEN 'REGISTERED' THEN '中科院杭州医学研究所招聘归档和脚本 Excel 附件已登记；计算机硕士工程师岗位列为 P0，状态保持 PARTIAL'
         WHEN 'CONTRACT_VERIFIED' THEN '等待机器核对 120 DOM 块、60 个可见去重记录、23 个站内官方详情及 2024 外链缺口'
         WHEN 'LIVE_SMOKE_VERIFIED' THEN '等待受控实网保存列表、招聘详情和 appLinkStr 官方岗位表'
         WHEN 'BACKFILL_COMPLETE' THEN '2025-2026 可遍历；2024 六条主要为外链，需交叉来源补齐后再验收'
         ELSE '等待第二次无重复增量运行验证详情 URL、岗位键、内容指纹和用工性质变化'
       END, now()
FROM recruitment_source source
CROSS JOIN (VALUES ('REGISTERED'),('CONTRACT_VERIFIED'),('LIVE_SMOKE_VERIFIED'),
    ('BACKFILL_COMPLETE'),('INCREMENTAL_VERIFIED')) AS checkpoint(value)
WHERE source.code='CAS_HANGZHOU_MEDICINE'
ON CONFLICT (source_id, checkpoint) DO NOTHING;
