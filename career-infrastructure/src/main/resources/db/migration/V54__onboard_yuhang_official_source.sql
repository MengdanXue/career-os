INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES (
    '01992f09-0000-7000-8000-000000000417',
    'HZ_YUHANG_GOV', '余杭区政府招聘',
    'https://www.yuhang.gov.cn/',
    'https://www.yuhang.gov.cn/col/col1229191870/index.html',
    'OFFICIAL_GOVERNMENT', '杭州余杭', 'STATIC_HTML', TRUE,
    '0 20 9 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "allowedHosts":["www.yuhang.gov.cn","zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"],
      "attachmentSelector":"a[href]",
      "listingEntries":[{
        "code":"personnel-recruitment",
        "entryUri":"https://www.yuhang.gov.cn/col/col1229191870/index.html",
        "role":"PRIMARY","mode":"JCMS_PARAM_JSON",
        "recruitmentYears":[2024,2025,2026,2027],"completenessRequired":true,
        "listingApiUri":"https://www.yuhang.gov.cn/api-gateway/jpaas-publish-server/front/page/build/unit?parseType=bulidstatic&webId=3095&tplSetId=rZlQrQ3MzlENOr1wE0PeW&pageType=column&tagId=%E7%BB%84%E9%85%8D%E5%88%86%E7%B1%BBlist&editType=null&pageId=1229191870",
        "jcmsSearch":{"xxgkId":"W001-C001","xxgkType":"","className":"人员考录"},
        "historicalPageSize":15,"historicalMaxPages":20,"incrementalListingMaxPages":2,
        "reconcileReportedTotalByListingItems":true,
        "allowedHosts":["www.yuhang.gov.cn","zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"],
        "articleUrlRegex":"^https://www\\.yuhang\\.gov\\.cn/(?:col/col[0-9]+/)?art/[0-9]{4}(?:/[0-9]+/[0-9]+)?/art_[A-Za-z0-9_]+\\.html$",
        "listingItemSelector":"ul.ajax-ul > li.cf.border-line","itemLinkSelector":"a.fl[href]",
        "itemTitleSelector":"a.fl[href]","itemPublishedDateSelector":"span.fr","linkSelector":"a.fl[href]",
        "reportedTotalRegex":"count=\\\\\"(\\d+)\\\\\"",
        "reportedCurrentPageRegex":"pageNo=\\\\\"(\\d+)\\\\\"",
        "titleIncludeRegex":"招聘|招考|招录|选聘|引进|人才|报名|岗位|核减|取消|资格|复审|笔试|成绩|面试|体检|考察|公示|录用|聘用|递补|放弃",
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
WHERE source.code = 'HZ_YUHANG_GOV'
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
WHERE target.code = 'HZ_YUHANG_GOV' AND source.code = target.code;

INSERT INTO source_onboarding_checkpoint (
    source_id, checkpoint, status, evidence, verified_at
)
SELECT source.id, checkpoint.value,
       CASE WHEN checkpoint.value = 'REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
       CASE checkpoint.value
           WHEN 'REGISTERED' THEN '余杭区人员考录官方 JCMS 分类已登记；状态保持 PARTIAL'
           WHEN 'CONTRACT_VERIFIED' THEN '等待机器核对 252 个列表行/17 页与 251 个授权唯一文章'
           WHEN 'LIVE_SMOKE_VERIFIED' THEN '等待受控实网采集保存列表、详情、附件和岗位表证据'
           WHEN 'BACKFILL_COMPLETE' THEN '等待核对 2024-2026 招聘公告及全生命周期记录'
           ELSE '等待第二次无重复增量运行验证稳定岗位键、内容指纹和变化事件'
       END, now()
FROM recruitment_source source
CROSS JOIN (VALUES ('REGISTERED'), ('CONTRACT_VERIFIED'), ('LIVE_SMOKE_VERIFIED'),
    ('BACKFILL_COMPLETE'), ('INCREMENTAL_VERIFIED')) AS checkpoint(value)
WHERE source.code = 'HZ_YUHANG_GOV'
ON CONFLICT (source_id, checkpoint) DO NOTHING;
