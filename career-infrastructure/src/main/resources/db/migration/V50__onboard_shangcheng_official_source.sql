INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES (
    '01992f09-0000-7000-8000-000000000413',
    'HZ_SHANGCHENG_GOV', '上城区政府招聘',
    'https://www.hzsc.gov.cn/',
    'https://www.hzsc.gov.cn/col/col1229554150/index.html',
    'OFFICIAL_GOVERNMENT', '杭州上城', 'STATIC_HTML', TRUE,
    '0 35 9 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "allowedHosts":["www.hzsc.gov.cn","zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"],
      "attachmentSelector":"a[href]",
      "listingEntries":[{
        "code":"establishment-and-lifecycle",
        "entryUri":"https://www.hzsc.gov.cn/col/col1229554150/index.html",
        "role":"PRIMARY","mode":"STATIC_SUFFIX_TEMPLATE",
        "recruitmentYears":[2024,2025,2026,2027],
        "knownArchiveGapYears":[2025],"completenessRequired":true,
        "pageUriTemplate":"https://www.hzsc.gov.cn/api-gateway/jpaas-publish-server/front/page/build/unit?parseType=bulidstatic&webId=2208&tplSetId=OTrLUGcDas9uLu1xV8413&pageType=column&tagId=%E7%A7%BB%E5%8A%A8%E7%AB%AF%E5%88%97%E8%A1%A81&editType=null&pageId=1229554150&paramJson=%7B%22pageNo%22%3A{page}%2C%22pageSize%22%3A15%7D",
        "historicalMaxPages":10,"incrementalListingMaxPages":2,
        "allowedHosts":["www.hzsc.gov.cn","zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"],
        "articleUrlRegex":"^https://www\\.hzsc\\.gov\\.cn/(?:col/col[0-9]+/)?art/[0-9]{4}(?:/[0-9]+/[0-9]+)?/art_[A-Za-z0-9_]+\\.html$",
        "listingItemSelector":".page-content > li",
        "itemLinkSelector":"a[href]","itemTitleSelector":"a[title]",
        "itemPublishedDateSelector":".time","linkSelector":"a[href]",
        "reportedTotalRegex":"count:\\s*\\\\\"(\\d+)\\\\\"",
        "reportedCurrentPageRegex":"pageNo:\\s*\\\\\"(\\d+)\\\\\"",
        "titleIncludeRegex":"招聘|招考|选聘|引进|人才|报名|笔试|考试|资格|复审|成绩|面试|体检|考察|公示|录用|聘用|递补|取消|核减",
        "titleExcludeRegex":"招聘会|培训|讲座|编外|派遣"
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
WHERE source.code = 'HZ_SHANGCHENG_GOV'
ON CONFLICT (source_id, recruitment_year) DO NOTHING;

UPDATE target_source_catalog target
SET recruitment_source_id = source.id,
    connection_status = 'PARTIAL',
    official_root_url = source.base_uri,
    updated_at = now()
FROM recruitment_source source
WHERE target.code = 'HZ_SHANGCHENG_GOV'
  AND source.code = target.code;

INSERT INTO source_onboarding_checkpoint (
    source_id, checkpoint, status, evidence, verified_at
)
SELECT source.id,
       checkpoint.value,
       CASE WHEN checkpoint.value = 'REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
       CASE checkpoint.value
           WHEN 'REGISTERED' THEN '上城区政府事业单位招聘及成绩、复审、面试、体检、公示全生命周期官方栏目已登记；状态保持 PARTIAL'
           WHEN 'CONTRACT_VERIFIED' THEN '等待机器核对官方栏目 112 条、8 页及唯一详情链接'
           WHEN 'LIVE_SMOKE_VERIFIED' THEN '等待受控实网采集保存列表、详情、附件重定向和岗位表证据'
           WHEN 'BACKFILL_COMPLETE' THEN '2025 官方栏目存在已核实的历史档案缺口；不得标记 COMPLETE，等待补偿来源'
           ELSE '等待第二次无重复增量运行验证稳定岗位键、内容指纹和变化事件'
       END,
       now()
FROM recruitment_source source
CROSS JOIN (VALUES
    ('REGISTERED'), ('CONTRACT_VERIFIED'), ('LIVE_SMOKE_VERIFIED'),
    ('BACKFILL_COMPLETE'), ('INCREMENTAL_VERIFIED')
) AS checkpoint(value)
WHERE source.code = 'HZ_SHANGCHENG_GOV'
ON CONFLICT (source_id, checkpoint) DO NOTHING;
