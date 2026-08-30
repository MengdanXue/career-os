INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES (
    '01992f09-0000-7000-8000-000000000430',
    'UCAS_HANGZHOU', '国科大杭州高等研究院招聘',
    'https://hias.ucas.ac.cn/',
    'https://hias.ucas.ac.cn/',
    'OFFICIAL_ORGANIZATION', '浙江杭州', 'STATIC_HTML', TRUE,
    '0 15 11 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "attachmentSelector":"a.__career_os_no_attachment__[href]",
      "listingEntries":[
        {
          "code":"research-and-professional",
          "entryUri":"http://hias.ucas.ac.cn/rczp/kyryzp.htm",
          "role":"PRIMARY","mode":"LINKED_PAGE",
          "recruitmentYears":[2024,2025,2026,2027],
          "knownArchiveGapYears":[2024,2025],"completenessRequired":false,
          "historicalMaxPages":10,"incrementalListingMaxPages":1,
          "nextPageSelector":".p_next a[href]",
          "transportPolicy":"AUDITED_HTTP_READ_ONLY",
          "allowedHosts":["hias.ucas.ac.cn"],
          "exactAuthorities":["hias.ucas.ac.cn:80"],
          "allowedPathPrefixes":["/rczp/","/info/1057/"],
          "listingItemSelector":"table.table tbody tr",
          "itemLinkSelector":"td:first-child a[href]",
          "itemTitleSelector":"td:first-child a[href]",
          "itemPublishedDateSelector":"td:last-child",
          "linkSelector":"td:first-child a[href]",
          "articleUrlRegex":"^http://hias\\.ucas\\.ac\\.cn/info/1057/[0-9]+\\.htm$",
          "titleIncludeRegex":"招聘|专业技术|实验技术|工程|信息|软件|数据|平台|支撑|拟聘|公示|复审|考核|成绩|体检",
          "titleExcludeRegex":"博士后|领军人才|青年人才|教师"
        },
        {
          "code":"management-support-and-lifecycle",
          "entryUri":"http://hias.ucas.ac.cn/rczp/glzcryzp.htm",
          "role":"LIFECYCLE","mode":"LINKED_PAGE",
          "recruitmentYears":[2024,2025,2026,2027],
          "knownArchiveGapYears":[2024,2025],"completenessRequired":false,
          "historicalMaxPages":10,"incrementalListingMaxPages":1,
          "nextPageSelector":".p_next a[href]",
          "transportPolicy":"AUDITED_HTTP_READ_ONLY",
          "allowedHosts":["hias.ucas.ac.cn"],
          "exactAuthorities":["hias.ucas.ac.cn:80"],
          "allowedPathPrefixes":["/rczp/","/info/1058/"],
          "listingItemSelector":"table.table tbody tr",
          "itemLinkSelector":"td:first-child a[href]",
          "itemTitleSelector":"td:first-child a[href]",
          "itemPublishedDateSelector":"td:last-child",
          "linkSelector":"td:first-child a[href]",
          "articleUrlRegex":"^http://hias\\.ucas\\.ac\\.cn/info/1058/[0-9]+\\.htm$",
          "titleIncludeRegex":"招聘|管理支撑|专业技术|实验技术|工程|信息|软件|数据|平台|支撑|资格|复审|考试|考核|面试|成绩|体检|公示|拟聘|递补",
          "titleExcludeRegex":"招聘会|培训|讲座"
        }
      ]
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
WHERE source.code='UCAS_HANGZHOU'
ON CONFLICT (source_id, recruitment_year) DO NOTHING;

UPDATE target_source_catalog target
SET recruitment_source_id=source.id,
    connection_status=CASE WHEN target.connection_status='CONNECTED' THEN 'CONNECTED' ELSE 'PARTIAL' END,
    official_root_url=source.base_uri, updated_at=now()
FROM recruitment_source source
WHERE target.code='UCAS_HANGZHOU' AND source.code=target.code;

INSERT INTO source_onboarding_checkpoint (source_id, checkpoint, status, evidence, verified_at)
SELECT source.id, checkpoint.value,
       CASE WHEN checkpoint.value='REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
       CASE checkpoint.value
         WHEN 'REGISTERED' THEN '国科大杭州高等研究院科研和管理支撑官方归档已登记；仅允许 hias.ucas.ac.cn:80 指定路径只读访问'
         WHEN 'CONTRACT_VERIFIED' THEN '等待机器复核双入口、反向分页、详情 URL、日期和生命周期分类'
         WHEN 'LIVE_SMOKE_VERIFIED' THEN '等待受控实网保存 2024-2026 招聘、复审、成绩、体检和拟聘正文'
         WHEN 'BACKFILL_COMPLETE' THEN '官网附件下载受验证码保护且初始公告存在缺口；需与杭州人社官方附件交叉补齐，来源保持 PARTIAL'
         ELSE '等待第二次无重复增量运行验证稳定 URL、正文指纹和列表元数据指纹'
       END, now()
FROM recruitment_source source
CROSS JOIN (VALUES ('REGISTERED'),('CONTRACT_VERIFIED'),('LIVE_SMOKE_VERIFIED'),
    ('BACKFILL_COMPLETE'),('INCREMENTAL_VERIFIED')) AS checkpoint(value)
WHERE source.code='UCAS_HANGZHOU'
ON CONFLICT (source_id, checkpoint) DO NOTHING;
