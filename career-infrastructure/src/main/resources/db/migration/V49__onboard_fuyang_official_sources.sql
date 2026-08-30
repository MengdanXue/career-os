INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES (
    '01992f09-0000-7000-8000-000000000412',
    'HZ_FUYANG_GOV', '富阳区政府招聘',
    'https://www.fuyang.gov.cn/',
    'https://www.fuyang.gov.cn/col/col1228923273/index.html',
    'OFFICIAL_GOVERNMENT', '杭州富阳', 'STATIC_HTML', TRUE,
    '0 20 9 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "allowedHosts":["www.fuyang.gov.cn","zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"],
      "attachmentSelector":"a[href]",
      "listingEntries":[
        {
          "code":"establishment",
          "entryUri":"https://www.fuyang.gov.cn/col/col1228923273/index.html",
          "role":"PRIMARY","mode":"STATIC_SUFFIX_TEMPLATE",
          "recruitmentYears":[2024,2025,2026,2027],
          "knownArchiveGapYears":[2024],"completenessRequired":true,
          "pageUriTemplate":"https://www.fuyang.gov.cn/api-gateway/jpaas-publish-server/front/page/build/unit?parseType=bulidstatic&webId=2754&tplSetId=KruLArIzu5zMBXJpWlwJX&pageType=column&tagId=%E4%BF%A1%E6%81%AF%E5%88%97%E8%A1%A8&editType=null&pageId=1228922793&paramJson=%7B%22pageNo%22%3A{page}%2C%22pageSize%22%3A15%2C%22search%22%3A%22%7B%5C%22xxgkId%5C%22%3A%5C%22F001-E001%5C%22%2C%5C%22xxgkType%5C%22%3A%5C%22%5C%22%2C%5C%22className%5C%22%3A%5C%22%5C%22%7D%22%7D",
          "historicalMaxPages":20,"incrementalListingMaxPages":2,
          "allowedHosts":["www.fuyang.gov.cn","zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"],
          "articleUrlRegex":"^https://www\\.fuyang\\.gov\\.cn/(?:col/col[0-9]+/)?art/[0-9]{4}(?:/[0-9]+/[0-9]+)?/art_[A-Za-z0-9_]+\\.html$",
          "listingItemSelector":"ul.ajax-ul > li.cf.border-line",
          "itemLinkSelector":"a.fl[href]","itemTitleSelector":"a.fl[href]",
          "itemPublishedDateSelector":"span.fr","linkSelector":"a.fl[href]",
          "reportedTotalRegex":"count=\\\\\"(\\d+)\\\\\"",
          "reportedCurrentPageRegex":"pageNo=\\\\\"(\\d+)\\\\\"",
          "reconcileReportedTotalByListingItems":true,
          "titleIncludeRegex":"招聘|招考|选聘|引进|人才|报名|笔试|考试|资格|复审|成绩|面试|体检|考察|公示|录用|聘用|递补|取消|核减",
          "titleExcludeRegex":"招聘会|培训|讲座"
        },
        {
          "code":"health-establishment",
          "entryUri":"https://www.fuyang.gov.cn/col/col1228922800/index.html",
          "role":"SECTOR","mode":"STATIC_SUFFIX_TEMPLATE",
          "recruitmentYears":[2024,2025,2026,2027],
          "knownArchiveGapYears":[2024],"completenessRequired":true,
          "pageUriTemplate":"https://www.fuyang.gov.cn/api-gateway/jpaas-publish-server/front/page/build/unit?parseType=bulidstatic&webId=2754&tplSetId=KruLArIzu5zMBXJpWlwJX&pageType=column&tagId=%E4%BF%A1%E6%81%AF%E5%88%97%E8%A1%A8&editType=null&pageId=1228922800&paramJson=%7B%22pageNo%22%3A{page}%2C%22pageSize%22%3A15%2C%22search%22%3A%22%7B%5C%22xxgkId%5C%22%3A%5C%22F001-G001%5C%22%2C%5C%22xxgkType%5C%22%3A%5C%22%5C%22%2C%5C%22className%5C%22%3A%5C%22%5C%22%7D%22%7D",
          "historicalMaxPages":30,"incrementalListingMaxPages":2,
          "allowedHosts":["www.fuyang.gov.cn","zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"],
          "articleUrlRegex":"^https://www\\.fuyang\\.gov\\.cn/(?:col/col[0-9]+/)?art/[0-9]{4}(?:/[0-9]+/[0-9]+)?/art_[A-Za-z0-9_]+\\.html$",
          "listingItemSelector":"ul.ajax-ul > li.cf.border-line",
          "itemLinkSelector":"a.fl[href]","itemTitleSelector":"a.fl[href]",
          "itemPublishedDateSelector":"span.fr","linkSelector":"a.fl[href]",
          "reportedTotalRegex":"count=\\\\\"(\\d+)\\\\\"",
          "reportedCurrentPageRegex":"pageNo=\\\\\"(\\d+)\\\\\"",
          "reconcileReportedTotalByListingItems":true,
          "titleIncludeRegex":"招聘|招考|选聘|引进|人才|报名|笔试|考试|资格|复审|成绩|面试|体检|考察|公示|录用|聘用|递补|取消|核减",
          "titleExcludeRegex":"招聘会|培训|讲座|编外"
        }
      ]
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
WHERE source.code = 'HZ_FUYANG_GOV'
-- V49 onboards a previously unconnected source. If an operator or a prior
-- repair already created progress, do not invalidate that evidence.
ON CONFLICT (source_id, recruitment_year) DO NOTHING;

UPDATE target_source_catalog target
SET recruitment_source_id = source.id,
    connection_status = 'PARTIAL',
    official_root_url = source.base_uri,
    updated_at = now()
FROM recruitment_source source
WHERE target.code = 'HZ_FUYANG_GOV'
  AND source.code = target.code;

INSERT INTO source_onboarding_checkpoint (
    source_id, checkpoint, status, evidence, verified_at
)
SELECT source.id,
       checkpoint.value,
       CASE WHEN checkpoint.value = 'REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
       CASE checkpoint.value
           WHEN 'REGISTERED' THEN '富阳人社事业单位和卫健事业单位两个官方 JCMS 叶分类已登记；状态保持 PARTIAL'
           WHEN 'CONTRACT_VERIFIED' THEN '等待机器核对人社 59 条/4 页、卫健 177 个列表行/12 页（其中 3 条为契约外微信链接）和 174 个政府详情链接'
           WHEN 'LIVE_SMOKE_VERIFIED' THEN '等待受控实网采集保存列表、详情、附件重定向和岗位表证据'
           WHEN 'BACKFILL_COMPLETE' THEN '2024 初始招聘公告存在已核实的官方档案缺口；不得标记 COMPLETE，等待 2025-2026 回填'
           ELSE '等待第二次无重复增量运行验证稳定岗位键、内容指纹和差异事件'
       END,
       now()
FROM recruitment_source source
CROSS JOIN (VALUES
    ('REGISTERED'), ('CONTRACT_VERIFIED'), ('LIVE_SMOKE_VERIFIED'),
    ('BACKFILL_COMPLETE'), ('INCREMENTAL_VERIFIED')
) AS checkpoint(value)
WHERE source.code = 'HZ_FUYANG_GOV'
-- Preserve checkpoints already created by a controlled run; missing rows are
-- still inserted by this statement.
ON CONFLICT (source_id, checkpoint) DO NOTHING;
