INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES (
    '01992f09-0000-7000-8000-000000000419',
    'ZJUT_RECRUITMENT', '浙江工业大学招聘',
    'http://www.rczp.zjut.edu.cn/',
    'http://www.rczp.zjut.edu.cn/5346/list.htm',
    'OFFICIAL_ORGANIZATION', '浙江杭州', 'STATIC_HTML', TRUE,
    '0 45 8 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "allowedHosts":["www.rczp.zjut.edu.cn"],
      "attachmentSelector":"a[href]",
      "listingEntries":[{
        "code":"recruitment-announcements",
        "entryUri":"http://www.rczp.zjut.edu.cn/5346/list.htm",
        "role":"PRIMARY","mode":"STATIC_SUFFIX_TEMPLATE",
        "recruitmentYears":[2024,2025,2026,2027],"completenessRequired":false,
        "transportPolicy":"AUDITED_HTTP_READ_ONLY",
        "allowedHosts":["www.rczp.zjut.edu.cn"],
        "exactAuthorities":["www.rczp.zjut.edu.cn:80"],
        "allowedPathPrefixes":["/5346/","/2024/","/2025/","/2026/","/2027/","/_upload/"],
        "pageUriTemplate":"http://www.rczp.zjut.edu.cn/5346/list{page}.htm",
        "historicalMaxPages":3,"incrementalListingMaxPages":2,
        "reconcileReportedTotalByListingItems":true,
        "listingItemSelector":"ul.wp_article_list > li.list_item",
        "itemLinkSelector":".Article_Title a[href]","itemTitleSelector":".Article_Title a[href]",
        "itemPublishedDateSelector":".Article_PublishDate","linkSelector":".Article_Title a[href]",
        "reportedTotalRegex":"<em class=\"all_count\">(\\d+)</em>",
        "reportedTotalPagesRegex":"<em class=\"all_pages\">(\\d+)</em>",
        "reportedCurrentPageRegex":"<em class=\"curr_page\">(\\d+)</em>",
        "articleUrlRegex":"^http://www\\.rczp\\.zjut\\.edu\\.cn/[0-9]{4}/[0-9]{4}/c5346a[0-9]+/page\\.htm$",
        "titleIncludeRegex":"招聘|招考|引进|人才|报名|岗位|资格|复审|笔试|成绩|面试|体检|考核|考察|名单|通知|核减|取消",
        "titleExcludeRegex":"招聘会邀请|宣讲会|人才派遣|劳务派遣"
      },{
        "code":"appointment-publicity",
        "entryUri":"http://www.rczp.zjut.edu.cn/5347/list.htm",
        "role":"LIFECYCLE","mode":"STATIC_SUFFIX_TEMPLATE",
        "recruitmentYears":[2024,2025,2026,2027],"completenessRequired":false,
        "transportPolicy":"AUDITED_HTTP_READ_ONLY",
        "allowedHosts":["www.rczp.zjut.edu.cn"],
        "exactAuthorities":["www.rczp.zjut.edu.cn:80"],
        "allowedPathPrefixes":["/5347/","/2024/","/2025/","/2026/","/2027/","/_upload/"],
        "pageUriTemplate":"http://www.rczp.zjut.edu.cn/5347/list{page}.htm",
        "historicalMaxPages":5,"incrementalListingMaxPages":2,
        "reconcileReportedTotalByListingItems":true,
        "listingItemSelector":"ul.wp_article_list > li.list_item",
        "itemLinkSelector":".Article_Title a[href]","itemTitleSelector":".Article_Title a[href]",
        "itemPublishedDateSelector":".Article_PublishDate","linkSelector":".Article_Title a[href]",
        "reportedTotalRegex":"<em class=\"all_count\">(\\d+)</em>",
        "reportedTotalPagesRegex":"<em class=\"all_pages\">(\\d+)</em>",
        "reportedCurrentPageRegex":"<em class=\"curr_page\">(\\d+)</em>",
        "articleUrlRegex":"^http://www\\.rczp\\.zjut\\.edu\\.cn/[0-9]{4}/[0-9]{4}/c5347a[0-9]+/page\\.htm$",
        "titleIncludeRegex":"拟聘|聘用|公示|非教学科研岗|实验技术|工程技术|信息化",
        "titleExcludeRegex":"人才派遣|劳务派遣"
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
WHERE source.code = 'ZJUT_RECRUITMENT'
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
WHERE target.code = 'ZJUT_RECRUITMENT' AND source.code = target.code;

INSERT INTO source_onboarding_checkpoint (source_id, checkpoint, status, evidence, verified_at)
SELECT source.id, checkpoint.value,
       CASE WHEN checkpoint.value = 'REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
       CASE checkpoint.value
           WHEN 'REGISTERED' THEN '浙工大招聘公告与拟聘公示官方栏目已登记；因官网仅提供经审计只读 HTTP，状态保持 PARTIAL'
           WHEN 'CONTRACT_VERIFIED' THEN '等待机器核对招聘公告 27 条/2 页、拟聘公示 54 条/4 页和精确 HTTP 权限边界'
           WHEN 'LIVE_SMOKE_VERIFIED' THEN '等待受控实网采集保存列表、详情、附件和岗位表证据'
           WHEN 'BACKFILL_COMPLETE' THEN '等待核对 2024-2026 招聘、复审、面试、体检和拟聘生命周期'
           ELSE '等待第二次无重复增量运行验证稳定岗位键、内容指纹和变化事件'
       END, now()
FROM recruitment_source source
CROSS JOIN (VALUES ('REGISTERED'), ('CONTRACT_VERIFIED'), ('LIVE_SMOKE_VERIFIED'),
    ('BACKFILL_COMPLETE'), ('INCREMENTAL_VERIFIED')) AS checkpoint(value)
WHERE source.code = 'ZJUT_RECRUITMENT'
ON CONFLICT (source_id, checkpoint) DO NOTHING;
