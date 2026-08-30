INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES (
    '01992f09-0000-7000-8000-000000000414',
    'HZ_LINAN_GOV', '临安区政府招聘',
    'https://www.linan.gov.cn/',
    'https://www.linan.gov.cn/col/col1229261156/index.html',
    'OFFICIAL_GOVERNMENT', '杭州临安', 'STATIC_HTML', TRUE,
    '0 50 9 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "allowedHosts":["www.linan.gov.cn","zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"],
      "attachmentSelector":"a[href]",
      "listingEntries":[{
        "code":"personnel-information",
        "entryUri":"https://www.linan.gov.cn/col/col1229261156/index.html",
        "role":"PRIMARY","mode":"JCMS_PARAM_JSON",
        "recruitmentYears":[2024,2025,2026,2027],"completenessRequired":true,
        "listingApiUri":"https://www.linan.gov.cn/api-gateway/jpaas-publish-server/front/page/build/unit?parseType=bulidstatic&webId=2242&tplSetId=VuAIYKXLw7THdhTjKO2yP&pageType=column&tagId=%E4%BF%A1%E6%81%AF%E5%88%97%E8%A1%A8&editType=null&pageId=1229261156",
        "jcmsSearch":{"xxgkId":"F001","xxgkType":"","className":"人事信息"},
        "historicalPageSize":15,"historicalMaxPages":12,
        "incrementalListingMaxPages":2,
        "allowedHosts":["www.linan.gov.cn","zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"],
        "articleUrlRegex":"^https://www\\.linan\\.gov\\.cn/(?:col/col[0-9]+/)?art/[0-9]{4}(?:/[0-9]+/[0-9]+)?/art_[A-Za-z0-9_]+\\.html$",
        "listingItemSelector":"ul.ajax-ul > li.cf.border-line",
        "itemLinkSelector":"a.fl[href]","itemTitleSelector":"a.fl[href]",
        "itemPublishedDateSelector":"span.fr","linkSelector":"a.fl[href]",
        "reportedTotalRegex":"count=\\\\\"(\\d+)\\\\\"",
        "reportedCurrentPageRegex":"pageNo=\\\\\"(\\d+)\\\\\"",
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
WHERE source.code = 'HZ_LINAN_GOV'
ON CONFLICT (source_id, recruitment_year) DO NOTHING;

UPDATE target_source_catalog target
SET recruitment_source_id = source.id,
    connection_status = 'PARTIAL',
    official_root_url = source.base_uri,
    updated_at = now()
FROM recruitment_source source
WHERE target.code = 'HZ_LINAN_GOV'
  AND source.code = target.code;

INSERT INTO source_onboarding_checkpoint (
    source_id, checkpoint, status, evidence, verified_at
)
SELECT source.id,
       checkpoint.value,
       CASE WHEN checkpoint.value = 'REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
       CASE checkpoint.value
           WHEN 'REGISTERED' THEN '临安区政府人事信息官方 JCMS 分类已登记；独立部门来源尚未全部接入，状态保持 PARTIAL'
           WHEN 'CONTRACT_VERIFIED' THEN '等待机器核对官方分类 141 条、10 页和嵌套 jcmsSearch 请求回显'
           WHEN 'LIVE_SMOKE_VERIFIED' THEN '等待受控实网采集保存列表、详情、附件和岗位表证据'
           WHEN 'BACKFILL_COMPLETE' THEN '等待核对 2024-2026 招聘及全生命周期记录，并补齐独立部门来源'
           ELSE '等待第二次无重复增量运行验证稳定岗位键、内容指纹和变化事件'
       END,
       now()
FROM recruitment_source source
CROSS JOIN (VALUES
    ('REGISTERED'), ('CONTRACT_VERIFIED'), ('LIVE_SMOKE_VERIFIED'),
    ('BACKFILL_COMPLETE'), ('INCREMENTAL_VERIFIED')
) AS checkpoint(value)
WHERE source.code = 'HZ_LINAN_GOV'
ON CONFLICT (source_id, checkpoint) DO NOTHING;
