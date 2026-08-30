INSERT INTO target_source_catalog (
    code, name, route_code, organization_type, region, authority_level,
    official_root_url, connection_status, recruitment_source_id, enabled,
    scope_level, scope_code, priority_tier, coverage_role, updated_at
) VALUES (
    'HZ_CAPITAL_GROUP', '杭州市国有资本投资运营有限公司招聘',
    'GOVERNMENT_SOE_DIGITAL', 'STATE_OWNED_ENTERPRISE', '杭州', 'OFFICIAL_ORGANIZATION',
    'https://www.hzzbco.com/', 'PARTIAL', NULL, TRUE,
    'ORGANIZATION', 'HZ_CAPITAL_GROUP', 'P0', 'PRIMARY', now()
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
    '01992f09-0000-7000-8000-000000000427',
    'HZ_CAPITAL_GROUP', '杭州市国有资本投资运营有限公司招聘',
    'https://www.hzzbco.com/',
    'https://www.hzzbco.com/consult/list?menuId=273&languageId=1',
    'OFFICIAL_ORGANIZATION', '浙江杭州', 'STATIC_HTML', TRUE,
    '0 20 10 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "allowedHosts":["www.hzzbco.com","hzzbco.com","hzzb-web.oss-cn-hangzhou.aliyuncs.com"],
      "allowedPathPrefixes":["/","/hzzb/"],
      "attachmentSelector":"a[href]",
      "listingAbsenceDeactivationEnabled":true,
      "listingEntries":[{
        "code":"current-recruitment-and-lifecycle",
        "entryUri":"https://www.hzzbco.com/consult/list?menuId=273&languageId=1",
        "role":"PRIMARY","mode":"JSON_API",
        "recruitmentYears":[2024,2025,2026,2027],
        "knownArchiveGapYears":[2024,2025],"completenessRequired":true,
        "historicalMaxPages":10,"incrementalListingMaxPages":2,
        "historicalPageSize":100,"pageParameter":"current","pageSizeParameter":"size",
        "httpMethod":"GET","requestHeaders":{"language":"1"},
        "jsonItemsPath":"data.list","jsonTotalPath":"data.total",
        "jsonUrlField":"id","jsonUrlTemplate":"/newDet_{value}_8",
        "jsonTitleField":"title","jsonPublishedDateField":"issueTimeStr",
        "allowedHosts":["www.hzzbco.com","hzzbco.com","hzzb-web.oss-cn-hangzhou.aliyuncs.com"],
        "allowedPathPrefixes":["/consult/","/newDet_","/hzzb/"],
        "articleUrlRegex":"^https://www\\.hzzbco\\.com/newDet_[0-9]+_8$",
        "titleIncludeRegex":"招聘|招考|选聘|引进|考试|笔试|面试|资格复审|体检|考察|成绩|公示|录用|聘用|递补",
        "titleExcludeRegex":"招聘服务|猎头合作|采购|供应商|招标|中标|成交|遴选|询价|竞争性磋商|竞争性谈判|劳务派遣|劳务外包|业务外包|实习"
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
WHERE source.code='HZ_CAPITAL_GROUP'
ON CONFLICT (source_id, recruitment_year) DO NOTHING;

UPDATE target_source_catalog target
SET recruitment_source_id=source.id,
    connection_status=CASE WHEN target.connection_status='CONNECTED' THEN 'CONNECTED' ELSE 'PARTIAL' END,
    official_root_url=source.base_uri, updated_at=now()
FROM recruitment_source source
WHERE target.code='HZ_CAPITAL_GROUP' AND source.code=target.code;

INSERT INTO source_onboarding_checkpoint (source_id, checkpoint, status, evidence, verified_at)
SELECT source.id, checkpoint.value,
       CASE WHEN checkpoint.value='REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
       CASE checkpoint.value
         WHEN 'REGISTERED' THEN '杭州资本公开招聘 JSON API、官方详情和 Excel 附件域已登记；稳定键使用 consult id，状态保持 PARTIAL'
         WHEN 'CONTRACT_VERIFIED' THEN '等待机器复核 language 请求头、reported total、详情 URL 与附件白名单'
         WHEN 'LIVE_SMOKE_VERIFIED' THEN '等待受控实网保存当前招聘正文和官方 Excel 岗位表'
         WHEN 'BACKFILL_COMPLETE' THEN '2026 当前列表可遍历；2024-2025 存在官方历史入口缺口，补齐前不得标记完整'
         ELSE '等待第二次无重复增量运行验证稳定 URL、内容指纹和连续缺失下线策略'
       END, now()
FROM recruitment_source source
CROSS JOIN (VALUES ('REGISTERED'),('CONTRACT_VERIFIED'),('LIVE_SMOKE_VERIFIED'),
    ('BACKFILL_COMPLETE'),('INCREMENTAL_VERIFIED')) AS checkpoint(value)
WHERE source.code='HZ_CAPITAL_GROUP'
ON CONFLICT (source_id, checkpoint) DO NOTHING;
