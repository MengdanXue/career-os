INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES (
    '01992f09-0000-7000-8000-000000000431',
    'HZ_TONGLU_GOV', '桐庐县政府招聘',
    'https://www.tonglu.gov.cn/',
    'https://search.zj.gov.cn/jsearchfront/interfaces/cateSearch.do',
    'OFFICIAL_GOVERNMENT', '杭州桐庐', 'STATIC_HTML', TRUE,
    '0 45 9 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "allowedHosts":["search.zj.gov.cn","www.tonglu.gov.cn","zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"],
      "allowedPathPrefixes":["/jsearchfront/","/col/","/api-gateway/","/"],
      "attachmentSelector":"a[href]",
      "listingEntries":[{
        "code":"official-search-recruitment-lifecycle",
        "entryUri":"https://search.zj.gov.cn/jsearchfront/interfaces/cateSearch.do",
        "role":"PRIMARY","mode":"JSON_HTML_FRAGMENTS",
        "recruitmentYears":[2024,2025,2026,2027],
        "knownArchiveGapYears":[2024,2025],"completenessRequired":false,
        "historicalMaxPages":5,"historicalPageSize":100,"incrementalListingMaxPages":1,
        "pageParameter":"p","pageSizeParameter":"pg","paginationLocation":"BODY",
        "httpMethod":"POST","requestHeaders":{"Content-Type":"application/x-www-form-urlencoded"},
        "requestBodyTemplate":"websiteid=330122000000000&searchid=&pg={pageSize}&p={page}&tpl=1569&cateid=377&fbjg=&word=%E6%A1%90%E5%BA%90%E5%8E%BF%E4%BA%8B%E4%B8%9A%E5%8D%95%E4%BD%8D&q=%E6%A1%90%E5%BA%90%E5%8E%BF%E4%BA%8B%E4%B8%9A%E5%8D%95%E4%BD%8D&sortType=2&checkError=1&pos=title%2Ccontent%2Ckeyword",
        "jsonItemsPath":"result","jsonTotalPath":"total",
        "fragmentLinkSelector":".comprehensiveItem .titleWrapper > a[href]",
        "fragmentTitleSelector":".comprehensiveItem .titleWrapper > a[href]",
        "fragmentPublishedDateSelector":".comprehensiveItem .sourceTime span:last-child",
        "fragmentRedirectQueryParameter":"url",
        "fragmentUpgradeHttpHosts":["www.tonglu.gov.cn"],
        "allowedHosts":["search.zj.gov.cn","www.tonglu.gov.cn","zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"],
        "allowedPathPrefixes":["/jsearchfront/","/col/","/api-gateway/","/"],
        "articleUrlRegex":"^https://www\\.tonglu\\.gov\\.cn/col/col[0-9]+/art/[0-9]{4}/art_[a-f0-9]{32}\\.html$",
        "titleIncludeRegex":"(?:20\\d{2}年)?桐庐县.*(?:事业单位|机关事业单位).*(?:公开招聘|招聘高学历|紧缺实用人才|人才引进|笔试|资格复审|面试|成绩|体检|考察|拟聘用|公示|递补|核减|取消|放弃)",
        "titleExcludeRegex":"教师|教育系统|医疗卫生系统|医共体.*(?:医生|护士|卫生)|社区工作者|村干部|专职网格员|辅警|劳务派遣|编外|临时|见习|实习|招聘会|培训"
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
WHERE source.code='HZ_TONGLU_GOV'
ON CONFLICT (source_id, recruitment_year) DO NOTHING;

UPDATE target_source_catalog target
SET recruitment_source_id=source.id,
    connection_status=CASE WHEN target.connection_status='CONNECTED' THEN 'CONNECTED' ELSE 'PARTIAL' END,
    official_root_url=source.base_uri, updated_at=now()
FROM recruitment_source source
WHERE target.code='HZ_TONGLU_GOV' AND source.code=target.code;

INSERT INTO source_onboarding_checkpoint (source_id, checkpoint, status, evidence, verified_at)
SELECT source.id, checkpoint.value,
       CASE WHEN checkpoint.value='REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
       CASE checkpoint.value
         WHEN 'REGISTERED' THEN '浙江政务统一搜索的桐庐事业单位官方检索接口已登记；JSON 内 HTML 片段解析为桐庐政府详情稳定 URL'
         WHEN 'CONTRACT_VERIFIED' THEN '等待机器复核 POST 表单、官方总数、重定向目标、发布日期和标题过滤'
         WHEN 'LIVE_SMOKE_VERIFIED' THEN '等待受控实网保存 2026 主公告及笔试复审、成绩体检、考察和递补完整生命周期'
         WHEN 'BACKFILL_COMPLETE' THEN '统一搜索当前仅返回 2026 六条；2024-2025 旧栏目已迁移，来源保持 PARTIAL'
         ELSE '等待第二次无重复增量运行验证 art 标识稳定键、正文指纹和列表元数据指纹'
       END, now()
FROM recruitment_source source
CROSS JOIN (VALUES ('REGISTERED'),('CONTRACT_VERIFIED'),('LIVE_SMOKE_VERIFIED'),
    ('BACKFILL_COMPLETE'),('INCREMENTAL_VERIFIED')) AS checkpoint(value)
WHERE source.code='HZ_TONGLU_GOV'
ON CONFLICT (source_id, checkpoint) DO NOTHING;
