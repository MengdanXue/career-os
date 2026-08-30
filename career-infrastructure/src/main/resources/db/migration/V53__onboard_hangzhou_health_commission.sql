INSERT INTO target_source_catalog (
    code, name, route_code, organization_type, region, authority_level,
    official_root_url, connection_status, recruitment_source_id, enabled,
    scope_level, scope_code, priority_tier, coverage_role, updated_at
) VALUES (
    'HZ_HEALTH_COMMISSION', '杭州市卫生健康委员会事业单位招聘',
    'UNIVERSITY_HOSPITAL_IT', 'GOVERNMENT', '浙江杭州', 'OFFICIAL_AGGREGATOR',
    'https://wsjkw.hangzhou.gov.cn/', 'NOT_CONNECTED', NULL, TRUE,
    'CITY', 'HANGZHOU_HEALTH', 'P0', 'PRIMARY', now()
)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    route_code = EXCLUDED.route_code,
    organization_type = EXCLUDED.organization_type,
    region = EXCLUDED.region,
    authority_level = EXCLUDED.authority_level,
    official_root_url = EXCLUDED.official_root_url,
    enabled = EXCLUDED.enabled,
    scope_level = EXCLUDED.scope_level,
    scope_code = EXCLUDED.scope_code,
    priority_tier = EXCLUDED.priority_tier,
    coverage_role = EXCLUDED.coverage_role,
    updated_at = now();

INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES (
    '01992f09-0000-7000-8000-000000000416',
    'HZ_HEALTH_COMMISSION', '杭州市卫生健康委员会事业单位招聘',
    'https://wsjkw.hangzhou.gov.cn/',
    'https://wsjkw.hangzhou.gov.cn/col/col1229318903/index.html?number=C011401',
    'OFFICIAL_GOVERNMENT', '浙江杭州', 'STATIC_HTML', TRUE,
    '0 5 8 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "allowedHosts":["wsjkw.hangzhou.gov.cn","zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"],
      "attachmentSelector":"a[href]",
      "listingEntries":[{
        "code":"recruitment-announcements",
        "entryUri":"https://wsjkw.hangzhou.gov.cn/col/col1229318903/index.html?number=C011401",
        "role":"PRIMARY","mode":"JCMS_PARAM_JSON",
        "recruitmentYears":[2024,2025,2026,2027],"completenessRequired":true,
        "listingApiUri":"https://wsjkw.hangzhou.gov.cn/api-gateway/jpaas-publish-server/front/page/build/unit?parseType=bulidstatic&webId=1305&tplSetId=ZSdbO0sdxNOkVbued9HHd&pageType=column&tagId=%E5%BD%93%E5%89%8D%E6%A0%8F%E7%9B%AE%E5%88%97%E8%A1%A81&editType=null&pageId=1229318903",
        "historicalPageSize":15,"historicalMaxPages":16,"incrementalListingMaxPages":2,
        "reconcileReportedTotalByListingItems":true,
        "allowedHosts":["wsjkw.hangzhou.gov.cn","zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"],
        "articleUrlRegex":"^https://wsjkw\\.hangzhou\\.gov\\.cn/(?:col/col[0-9]+/)?art/[0-9]{4}(?:/[0-9]+/[0-9]+)?/art_[A-Za-z0-9_]+\\.html$",
        "listingItemSelector":".page-content li","itemLinkSelector":"a[href]",
        "itemTitleSelector":"a[href]","itemPublishedDateSelector":"b","linkSelector":"a[href]",
        "reportedTotalRegex":"count=\\\\\"(\\d+)\\\\\"",
        "reportedCurrentPageRegex":"pageNo=\\\\\"(\\d+)\\\\\"",
        "titleIncludeRegex":"招聘|招考|招录|选聘|引进|人才|报名|岗位|笔试|考试|资格|复审|成绩|面试|体检|考察|公示|录用|聘用|递补|取消|核减",
        "titleExcludeRegex":"招聘会|培训|讲座|编外|派遣"
      },{
        "code":"appointment-publicity",
        "entryUri":"https://wsjkw.hangzhou.gov.cn/col/col1229318910/index.html",
        "role":"LIFECYCLE","mode":"JCMS_PARAM_JSON",
        "recruitmentYears":[2024,2025,2026,2027],"completenessRequired":true,
        "listingApiUri":"https://wsjkw.hangzhou.gov.cn/api-gateway/jpaas-publish-server/front/page/build/unit?parseType=bulidstatic&webId=1305&tplSetId=ZSdbO0sdxNOkVbued9HHd&pageType=column&tagId=%E5%BD%93%E5%89%8D%E6%A0%8F%E7%9B%AE%E5%88%97%E8%A1%A81&editType=null&pageId=1229318910",
        "historicalPageSize":15,"historicalMaxPages":22,"incrementalListingMaxPages":2,
        "reconcileReportedTotalByListingItems":true,
        "allowedHosts":["wsjkw.hangzhou.gov.cn","zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"],
        "articleUrlRegex":"^https://wsjkw\\.hangzhou\\.gov\\.cn/(?:col/col[0-9]+/)?art/[0-9]{4}(?:/[0-9]+/[0-9]+)?/art_[A-Za-z0-9_]+\\.html$",
        "listingItemSelector":".page-content li","itemLinkSelector":"a[href]",
        "itemTitleSelector":"a[href]","itemPublishedDateSelector":"b","linkSelector":"a[href]",
        "reportedTotalRegex":"count=\\\\\"(\\d+)\\\\\"",
        "reportedCurrentPageRegex":"pageNo=\\\\\"(\\d+)\\\\\"",
        "titleIncludeRegex":"招聘|招考|招录|选聘|引进|人才|资格|复审|成绩|面试|体检|考察|公示|录用|聘用|递补|取消|核减",
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
WHERE source.code = 'HZ_HEALTH_COMMISSION'
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
WHERE target.code = 'HZ_HEALTH_COMMISSION'
  AND source.code = target.code;

INSERT INTO source_onboarding_checkpoint (
    source_id, checkpoint, status, evidence, verified_at
)
SELECT source.id,
       checkpoint.value,
       CASE WHEN checkpoint.value = 'REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
       CASE checkpoint.value
           WHEN 'REGISTERED' THEN '杭州市卫健委招聘公告和招聘公示两个官方栏目已登记；状态保持 PARTIAL'
           WHEN 'CONTRACT_VERIFIED' THEN '等待机器核对公告 201 条/14 页、公示 274 个列表行/19 页与 272 个授权唯一文章及双栏目完整性'
           WHEN 'LIVE_SMOKE_VERIFIED' THEN '等待受控实网采集保存列表、详情、附件和岗位表证据'
           WHEN 'BACKFILL_COMPLETE' THEN '等待核对 2024-2026 公告、考试、资格、体检和公示生命周期'
           ELSE '等待第二次无重复增量运行验证稳定岗位键、内容指纹和变化事件'
       END,
       now()
FROM recruitment_source source
CROSS JOIN (VALUES
    ('REGISTERED'), ('CONTRACT_VERIFIED'), ('LIVE_SMOKE_VERIFIED'),
    ('BACKFILL_COMPLETE'), ('INCREMENTAL_VERIFIED')
) AS checkpoint(value)
WHERE source.code = 'HZ_HEALTH_COMMISSION'
ON CONFLICT (source_id, checkpoint) DO NOTHING;
