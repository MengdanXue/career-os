INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES (
    '01992f09-0000-7000-8000-000000000305',
    'HZ_FIRST_HOSPITAL', '杭州市第一人民医院招聘',
    'https://www.hz-hospital.com/', 'https://www.hz-hospital.com/',
    'OFFICIAL_ORGANIZATION', '杭州', 'STATIC_HTML', TRUE,
    '0 20 11 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "attachmentSelector":"a[href]",
      "imageEvidenceSelector":"img[src*=''file_zp/attached/'']",
      "listingEntries":[{
        "code":"official-notices",
        "entryUri":"http://124.160.72.42:8080/apply/getMore.action?pageNumber=1",
        "role":"PRIMARY","mode":"QUERY_PAGE",
        "recruitmentYears":[2025,2026,2027],"completenessRequired":true,
        "transportPolicy":"AUDITED_HTTP_READ_ONLY",
        "allowedHosts":["124.160.72.42"],
        "exactAuthorities":["124.160.72.42:8080"],
        "allowedPathPrefixes":[
          "/apply/getMore.action","/apply/getNotice.action",
          "/apply/downloadAccessory.action","/file_zp/attached/"
        ],
        "pageParameter":"pageNumber","historicalMaxPages":30,
        "incrementalListingMaxPages":2,
        "reportedTotalRegex":"共\\s*(\\d+)\\s*条",
        "reportedTotalPagesRegex":"共\\s*(\\d+)\\s*页",
        "listingItemSelector":"div.keleyi",
        "itemLinkSelector":"a[href*=''getNotice.action?keycode='']",
        "itemTitleSelector":"a[href*=''getNotice.action?keycode='']",
        "itemPublishedDateSelector":"span:matchesOwn(^20\\d{2}-\\d{2}-\\d{2}$)",
        "linkSelector":"div.keleyi a[href*=''getNotice.action?keycode='']",
        "articleUrlRegex":"^http://124\\.160\\.72\\.42:8080/apply/getNotice\\.action\\?keycode=[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$",
        "titleIncludeRegex":"招聘|招考|引进|报名|笔试|面试|考务|资格|复审|体检|考察|公示|录用|递补|成绩|后续|名单|通知",
        "titleExcludeRegex":"招聘会|技术支持|岗位培训"
      }]
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
    last_failure_at = NULL,
    consecutive_failure_count = 0,
    updated_at = now();

INSERT INTO source_year_coverage (
    source_id, recruitment_year, status, discovered_count, fetched_count,
    parsed_count, target_job_count, updated_at
)
SELECT source.id, year.value, 'NOT_DISCOVERED', 0, 0, 0, 0, now()
FROM recruitment_source source
CROSS JOIN generate_series(2024, 2027) AS year(value)
WHERE source.code = 'HZ_FIRST_HOSPITAL'
ON CONFLICT (source_id, recruitment_year) DO UPDATE SET
    status = 'NOT_DISCOVERED',
    discovered_count = 0,
    fetched_count = 0,
    parsed_count = 0,
    target_job_count = 0,
    completion_basis = NULL,
    completed_at = NULL,
    listing_page_count = 0,
    filtered_count = 0,
    failed_count = 0,
    earliest_published_on = NULL,
    latest_published_on = NULL,
    stop_reason = NULL,
    updated_at = now();

UPDATE target_source_catalog target
SET recruitment_source_id = source.id,
    connection_status = 'PARTIAL',
    official_root_url = source.base_uri,
    updated_at = now()
FROM recruitment_source source
WHERE target.code = 'HZ_FIRST_HOSPITAL'
  AND source.code = target.code;

INSERT INTO source_onboarding_checkpoint (
    source_id, checkpoint, status, evidence, verified_at
)
SELECT source.id,
       checkpoint.value,
       CASE WHEN checkpoint.value = 'REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
       CASE checkpoint.value
           WHEN 'REGISTERED' THEN '官方招聘系统 124.160.72.42:8080 已按精确 authority、只读方法和窄路径登记；状态保持 PARTIAL'
           WHEN 'CONTRACT_VERIFIED' THEN '等待采集运行核对官网报告的 88 条、13 页和唯一详情 UUID'
           WHEN 'LIVE_SMOKE_VERIFIED' THEN '等待受控实网运行保存官方列表、详情和附件证据'
           WHEN 'BACKFILL_COMPLETE' THEN '当前系统最早仅到 2025 年；2024 无官方归档，图片岗位表需 OCR 后才可完整'
           ELSE '等待第二次无重复增量运行验证稳定岗位键、内容指纹和变化事件'
       END,
       now()
FROM recruitment_source source
CROSS JOIN (VALUES
    ('REGISTERED'), ('CONTRACT_VERIFIED'), ('LIVE_SMOKE_VERIFIED'),
    ('BACKFILL_COMPLETE'), ('INCREMENTAL_VERIFIED')
) AS checkpoint(value)
WHERE source.code = 'HZ_FIRST_HOSPITAL'
ON CONFLICT (source_id, checkpoint) DO UPDATE SET
    status = EXCLUDED.status,
    evidence = EXCLUDED.evidence,
    verified_at = EXCLUDED.verified_at;
