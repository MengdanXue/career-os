INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES (
    '01992f09-0000-7000-8000-000000000420',
    'HZNU_RECRUITMENT', '杭州师范大学招聘',
    'https://rsc.hznu.edu.cn/', 'https://rsc.hznu.edu.cn/rczpw/',
    'OFFICIAL_ORGANIZATION', '浙江杭州', 'STATIC_HTML', TRUE,
    '0 55 8 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "allowedHosts":["rsc.hznu.edu.cn","hrss.hangzhou.gov.cn"],
      "attachmentSelector":"a[href]",
      "listingEntries":[{
        "code":"current-recruitment-and-lifecycle",
        "entryUri":"https://rsc.hznu.edu.cn/rczpw/",
        "role":"CAMPAIGN_STATE","mode":"LINKED_PAGE",
        "recruitmentYears":[2024,2025,2026,2027],
        "knownArchiveGapYears":[2024,2025,2026],"completenessRequired":false,
        "allowedHosts":["rsc.hznu.edu.cn","hrss.hangzhou.gov.cn"],
        "historicalMaxPages":1,"incrementalListingMaxPages":1,
        "nextPageSelector":"a.__career_os_no_next__[href]",
        "listingItemSelector":".jxdt .bd .jxdt_cen:first-child li",
        "itemLinkSelector":"a[href]","itemTitleSelector":"a[href]",
        "linkSelector":".jxdt .bd .jxdt_cen:first-child a[href]",
        "articleUrlRegex":"^https://(?:rsc\\.hznu\\.edu\\.cn/c/[0-9]{4}-[0-9]{2}-[0-9]{2}/[0-9]+\\.shtml|hrss\\.hangzhou\\.gov\\.cn/(?:col/col[0-9]+/)?art/[0-9]{4}(?:/[0-9]+/[0-9]+)?/art_[A-Za-z0-9_]+\\.html)$",
        "titleIncludeRegex":"公开招聘|实验技术|工程技术|信息化|辅导员|资格|复审|笔试|成绩|面试|体检|考察|公示|聘用|递补|核减|取消",
        "titleExcludeRegex":"优秀青年科学基金|海外青年人才|劳务派遣|人才派遣"
      }]
    }'::jsonb,
    now()
)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name, base_uri = EXCLUDED.base_uri, entry_uri = EXCLUDED.entry_uri,
    source_type = EXCLUDED.source_type, region = EXCLUDED.region, crawl_mode = EXCLUDED.crawl_mode,
    enabled = EXCLUDED.enabled, cron_expression = EXCLUDED.cron_expression,
    time_zone = EXCLUDED.time_zone,
    minimum_request_interval_ms = EXCLUDED.minimum_request_interval_ms,
    configuration = EXCLUDED.configuration, next_due_at = EXCLUDED.next_due_at,
    last_failure_at = NULL, consecutive_failure_count = 0, updated_at = now();

INSERT INTO source_year_coverage (
    source_id, recruitment_year, status, discovered_count, fetched_count,
    parsed_count, target_job_count, updated_at
)
SELECT source.id, year.value, 'NOT_DISCOVERED', 0, 0, 0, 0, now()
FROM recruitment_source source
CROSS JOIN generate_series(2024, 2027) AS year(value)
WHERE source.code = 'HZNU_RECRUITMENT'
ON CONFLICT (source_id, recruitment_year) DO NOTHING;

UPDATE target_source_catalog target
SET recruitment_source_id = source.id,
    connection_status = CASE
        WHEN target.connection_status = 'CONNECTED' THEN 'CONNECTED'
        ELSE 'PARTIAL'
    END,
    official_root_url = source.base_uri,
    updated_at = now()
FROM recruitment_source source
WHERE target.code = 'HZNU_RECRUITMENT' AND source.code = target.code;

INSERT INTO source_onboarding_checkpoint (source_id, checkpoint, status, evidence, verified_at)
SELECT source.id, checkpoint.value,
       CASE WHEN checkpoint.value = 'REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
       CASE checkpoint.value
           WHEN 'REGISTERED' THEN '杭师大人才网当前招聘与校内生命周期入口已登记；历史主公告由杭州人社局托管，校站状态保持 PARTIAL'
           WHEN 'CONTRACT_VERIFIED' THEN '等待机器核对校站首页当前招聘、实验技术岗及资格复审/面试链接'
           WHEN 'LIVE_SMOKE_VERIFIED' THEN '等待受控实网采集保存校内详情及人社局托管公告证据'
           WHEN 'BACKFILL_COMPLETE' THEN '校站未公开完整历史分页；2024-2026 完整性由杭州人社局来源交叉覆盖后再验收'
           ELSE '等待第二次无重复增量运行验证稳定岗位键、内容指纹和变化事件'
       END, now()
FROM recruitment_source source
CROSS JOIN (VALUES ('REGISTERED'), ('CONTRACT_VERIFIED'), ('LIVE_SMOKE_VERIFIED'),
    ('BACKFILL_COMPLETE'), ('INCREMENTAL_VERIFIED')) AS checkpoint(value)
WHERE source.code = 'HZNU_RECRUITMENT'
ON CONFLICT (source_id, checkpoint) DO NOTHING;
