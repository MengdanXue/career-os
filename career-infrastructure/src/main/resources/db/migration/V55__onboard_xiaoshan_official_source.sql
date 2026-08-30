INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES (
    '01992f09-0000-7000-8000-000000000418',
    'HZ_XIAOSHAN_GOV', '萧山区政府事业单位考试',
    'https://www.xiaoshan.gov.cn/',
    'https://www.xiaoshan.gov.cn/col/col1229292680/index.html',
    'OFFICIAL_GOVERNMENT', '杭州萧山', 'STATIC_HTML', TRUE,
    '0 35 9 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "allowedHosts":["www.xiaoshan.gov.cn","zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"],
      "attachmentSelector":"a[href]",
      "listingEntries":[{
        "code":"institution-exams",
        "entryUri":"https://www.xiaoshan.gov.cn/col/col1229292680/index.html",
        "role":"PRIMARY","mode":"STATIC_SUFFIX_TEMPLATE",
        "recruitmentYears":[2024,2025,2026,2027],"knownArchiveGapYears":[2024],
        "completenessRequired":true,
        "pageUriTemplate":"https://www.xiaoshan.gov.cn/api-gateway/jpaas-publish-server/front/page/build/unit?parseType=bulidstatic&webId=2243&tplSetId=GazmxWZitZXDWHJedYwxo&pageType=column&tagId=%E5%BD%93%E5%89%8D%E6%A0%8F%E7%9B%AE%E5%88%97%E8%A1%A81a&editType=null&pageId=1229292680&paramJson=%7B%22pageNo%22%3A{page}%2C%22pageSize%22%3A20%7D",
        "historicalMaxPages":5,"incrementalListingMaxPages":2,
        "reconcileReportedTotalByListingItems":true,
        "allowedHosts":["www.xiaoshan.gov.cn","zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"],
        "articleUrlRegex":"^https://www\\.xiaoshan\\.gov\\.cn/(?:col/col[0-9]+/)?art/[0-9]{4}(?:/[0-9]+/[0-9]+)?/art_[A-Za-z0-9_]+\\.html$",
        "listingItemSelector":"#当前栏目列表1a .page-content > li","itemLinkSelector":"a[href]",
        "itemTitleSelector":"a[href]","itemPublishedDateSelector":"b.zfxxgk_zdgkc_time","linkSelector":"a[href]",
        "reportedTotalRegex":"count:\\s*\\\\\"(\\d+)\\\\\"",
        "reportedCurrentPageRegex":"pageNo:\\s*\\\\\"(\\d+)\\\\\"",
        "titleIncludeRegex":"招聘|招考|招录|选聘|选用|引进|人才|高学历事业人员|报名|岗位|通告|核减|取消|资格|复审|笔试|成绩|面试|体检|考察|公示|录用|聘用|递补|放弃",
        "titleExcludeRegex":"招聘会|培训|讲座"
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
WHERE source.code = 'HZ_XIAOSHAN_GOV'
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
WHERE target.code = 'HZ_XIAOSHAN_GOV' AND source.code = target.code;

INSERT INTO source_onboarding_checkpoint (
    source_id, checkpoint, status, evidence, verified_at
)
SELECT source.id, checkpoint.value,
       CASE WHEN checkpoint.value = 'REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
       CASE checkpoint.value
           WHEN 'REGISTERED' THEN '萧山区事业单位考试官方栏目已登记；2024 档案存在已核实缺口，状态保持 PARTIAL'
           WHEN 'CONTRACT_VERIFIED' THEN '等待机器核对 22 条/2 页、配置化计数格式及 2024 档案缺口'
           WHEN 'LIVE_SMOKE_VERIFIED' THEN '等待受控实网采集保存列表、详情、附件和岗位表证据'
           WHEN 'BACKFILL_COMPLETE' THEN '等待核对 2025-2026 招聘及全生命周期记录并补证 2024 缺口'
           ELSE '等待第二次无重复增量运行验证稳定岗位键、内容指纹和变化事件'
       END, now()
FROM recruitment_source source
CROSS JOIN (VALUES ('REGISTERED'), ('CONTRACT_VERIFIED'), ('LIVE_SMOKE_VERIFIED'),
    ('BACKFILL_COMPLETE'), ('INCREMENTAL_VERIFIED')) AS checkpoint(value)
WHERE source.code = 'HZ_XIAOSHAN_GOV'
ON CONFLICT (source_id, checkpoint) DO NOTHING;
