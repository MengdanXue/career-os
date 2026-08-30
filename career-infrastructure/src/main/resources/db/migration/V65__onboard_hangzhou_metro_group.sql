INSERT INTO target_source_catalog (
    code, name, route_code, organization_type, region, authority_level,
    official_root_url, connection_status, recruitment_source_id, enabled,
    scope_level, scope_code, priority_tier, coverage_role, updated_at
) VALUES (
    'HZ_METRO_GROUP', '杭州地铁集团招聘',
    'GOVERNMENT_SOE_DIGITAL', 'STATE_OWNED_ENTERPRISE', '杭州', 'OFFICIAL_ORGANIZATION',
    'https://www.hzmetro.com/', 'PARTIAL', NULL, TRUE,
    'ORGANIZATION', 'HZ_METRO_GROUP', 'P0', 'PRIMARY', now()
)
ON CONFLICT (code) DO UPDATE SET
    name=EXCLUDED.name, route_code=EXCLUDED.route_code,
    organization_type=EXCLUDED.organization_type, region=EXCLUDED.region,
    authority_level=EXCLUDED.authority_level, official_root_url=EXCLUDED.official_root_url,
    enabled=EXCLUDED.enabled, scope_level=EXCLUDED.scope_level,
    scope_code=EXCLUDED.scope_code, priority_tier=EXCLUDED.priority_tier,
    coverage_role=EXCLUDED.coverage_role, updated_at=now();

INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES (
    '01992f09-0000-7000-8000-000000000428',
    'HZ_METRO_GROUP', '杭州地铁集团招聘',
    'https://www.hzmetro.com/', 'https://www.hzmetro.com/api/article/search',
    'OFFICIAL_ORGANIZATION', '浙江杭州', 'STATIC_HTML', TRUE,
    '0 35 10 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "allowedHosts":["www.hzmetro.com","prod-bucket-mh.hangzhou7.zos.ctyun.cn","prod-hzmetro-portal.hangzhou7.zos.ctyun.cn"],
      "allowedPathPrefixes":["/","/public/common/"],
      "attachmentSelector":"a[href]","imageEvidenceSelector":"img[src]",
      "listingAbsenceDeactivationEnabled":true,
      "listingEntries":[{
        "code":"current-recruitment-and-lifecycle",
        "entryUri":"https://www.hzmetro.com/api/article/search",
        "role":"PRIMARY","mode":"JSON_API",
        "recruitmentYears":[2024,2025,2026,2027],
        "knownArchiveGapYears":[2024,2025],"completenessRequired":true,
        "historicalMaxPages":10,"incrementalListingMaxPages":2,
        "historicalPageSize":100,"pageParameter":"current","pageSizeParameter":"size",
        "paginationLocation":"BODY","httpMethod":"POST",
        "requestHeaders":{"Content-Type":"application/json"},
        "requestBodyTemplate":"{\"search\":\"招聘\",\"current\":\"{page}\",\"size\":{pageSize}}",
        "jsonItemsPath":"data.articleVOPage.records","jsonTotalPath":"data.articleVOPage.total",
        "jsonUrlField":"id","jsonUrlTemplate":"/newsCenter/newsContent?id={value}",
        "jsonFetchUrlTemplate":"/api/section/articleInfo?articleId={value}",
        "jsonFetchContentPath":"data.contentHtml",
        "jsonTitleField":"articleTitle","jsonPublishedDateField":"publishTime",
        "allowedHosts":["www.hzmetro.com","prod-bucket-mh.hangzhou7.zos.ctyun.cn","prod-hzmetro-portal.hangzhou7.zos.ctyun.cn"],
        "allowedPathPrefixes":["/api/article/","/api/section/","/newsCenter/newsContent","/public/common/"],
        "articleUrlRegex":"^https://www\\.hzmetro\\.com/newsCenter/newsContent\\?id=[0-9]+$",
        "titleIncludeRegex":"招聘|招考|选聘|考试|笔试|面试|资格复审|体检|考察|成绩|公示|录用|聘用|递补",
        "titleExcludeRegex":"招聘工作|招聘稳步推进|招聘服务|猎头合作|采购|供应商|培训|实训|校企合作|劳务派遣|劳务外包|业务外包|实习"
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
WHERE source.code='HZ_METRO_GROUP'
ON CONFLICT (source_id, recruitment_year) DO NOTHING;

UPDATE target_source_catalog target
SET recruitment_source_id=source.id,
    connection_status=CASE WHEN target.connection_status='CONNECTED' THEN 'CONNECTED' ELSE 'PARTIAL' END,
    official_root_url=source.base_uri, updated_at=now()
FROM recruitment_source source
WHERE target.code='HZ_METRO_GROUP' AND source.code=target.code;

INSERT INTO source_onboarding_checkpoint (source_id, checkpoint, status, evidence, verified_at)
SELECT source.id, checkpoint.value,
       CASE WHEN checkpoint.value='REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
       CASE checkpoint.value
         WHEN 'REGISTERED' THEN '杭州地铁公开招聘 POST JSON API、官方详情和岗位图片域已登记；稳定键使用 article id，状态保持 PARTIAL'
         WHEN 'CONTRACT_VERIFIED' THEN '等待机器复核 POST 正文、reported total、详情 URL 与图片白名单'
         WHEN 'LIVE_SMOKE_VERIFIED' THEN '等待受控实网保存当前招聘正文和岗位表图片'
         WHEN 'BACKFILL_COMPLETE' THEN '2026 当前招聘可遍历；2024-2025 在新官方 API 中缺失，补齐前不得标记完整'
         ELSE '等待第二次无重复增量运行验证稳定 URL、内容指纹和连续缺失下线策略'
       END, now()
FROM recruitment_source source
CROSS JOIN (VALUES ('REGISTERED'),('CONTRACT_VERIFIED'),('LIVE_SMOKE_VERIFIED'),
    ('BACKFILL_COMPLETE'),('INCREMENTAL_VERIFIED')) AS checkpoint(value)
WHERE source.code='HZ_METRO_GROUP'
ON CONFLICT (source_id, checkpoint) DO NOTHING;
