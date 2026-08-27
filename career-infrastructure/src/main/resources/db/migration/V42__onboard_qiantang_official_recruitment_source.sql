INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES (
    '01992f09-0000-7000-8000-000000000403',
    'HZ_QIANTANG_GOV',
    '杭州市钱塘区政府招聘',
    'https://www.qiantang.gov.cn/',
    'https://www.qiantang.gov.cn/col/col1657687/index.html',
    'OFFICIAL_GOVERNMENT', '杭州钱塘', 'STATIC_HTML', TRUE,
    '0 20 9 * * *', 'Asia/Shanghai', 1500,
    '{
      "adapterType":"JCMS_LISTING",
      "historicalPaginationMode":"JCMS_PARAM_JSON",
      "listingApiUri":"https://www.qiantang.gov.cn/api-gateway/jpaas-publish-server/front/page/build/unit?parseType=bulidstatic&webId=3176&tplSetId=0ZBvCXCKwwIstYiI5LRg9&pageType=column&tagId=%E5%88%97%E8%A1%A8%E9%A1%B5&editType=null&pageId=1657687",
      "historicalPageSize":20,
      "historicalMaxPages":200,
      "incrementalListingMaxPages":10,
      "historicalYears":[2024,2025,2026,2027],
      "articleUrlRegex":"^https://www\\.qiantang\\.gov\\.cn/(?:col/col[0-9]+/)?art/[0-9]{4}(?:/[0-9]+/[0-9]+)?/art_[A-Za-z0-9_]+\\.html$",
      "linkSelector":"a[href]",
      "attachmentSelector":"a[href]",
      "titleIncludeRegex":"招聘|招考|选聘|引进|雇员",
      "titleExcludeRegex":"招聘会|培训|讲座",
      "allowedHosts":["zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"]
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
    updated_at = now();

INSERT INTO source_year_coverage (
    source_id, recruitment_year, status, discovered_count, fetched_count,
    parsed_count, target_job_count, updated_at
)
SELECT source.id, year.value, 'NOT_DISCOVERED', 0, 0, 0, 0, now()
FROM recruitment_source source
CROSS JOIN generate_series(2024, 2027) AS year(value)
WHERE source.code = 'HZ_QIANTANG_GOV'
ON CONFLICT (source_id, recruitment_year) DO NOTHING;

UPDATE target_source_catalog target
SET recruitment_source_id = source.id,
    connection_status = 'PARTIAL',
    official_root_url = source.base_uri,
    updated_at = now()
FROM recruitment_source source
WHERE target.code = 'HZ_QIANTANG_GOV'
  AND source.code = target.code;

INSERT INTO source_onboarding_checkpoint (
    source_id, checkpoint, status, evidence, verified_at
)
SELECT id, 'REGISTERED', 'VERIFIED',
       '钱塘区重要公告 JCMS 官方总栏目已登记；使用十页受控增量扫描避免首页无招聘导致漏采，等待历史回填和幂等验收', now()
FROM recruitment_source
WHERE code = 'HZ_QIANTANG_GOV'
ON CONFLICT (source_id, checkpoint) DO UPDATE SET
    status = EXCLUDED.status,
    evidence = EXCLUDED.evidence,
    verified_at = EXCLUDED.verified_at;
