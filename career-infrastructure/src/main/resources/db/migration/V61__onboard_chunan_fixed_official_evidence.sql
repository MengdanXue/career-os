INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES (
    '01992f09-0000-7000-8000-000000000424',
    'HZ_CHUNAN_GOV', '淳安县政府招聘',
    'https://www.qdh.gov.cn/',
    'https://www.qdh.gov.cn/art/2024/4/3/art_1289606_59033591.html',
    'OFFICIAL_GOVERNMENT', '杭州淳安', 'STATIC_HTML', TRUE,
    '0 35 9 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "allowedHosts":["www.qdh.gov.cn","zjjcmspublic.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"],
      "attachmentSelector":"a[href]",
      "listingEntries":[{
        "code":"verified-official-evidence",
        "entryUri":"https://www.qdh.gov.cn/art/2024/4/3/art_1289606_59033591.html",
        "role":"HISTORICAL","mode":"FIXED_EVIDENCE",
        "recruitmentYears":[2024,2025,2026,2027],
        "knownArchiveGapYears":[2024,2025,2026],"completenessRequired":false,
        "allowedHosts":["www.qdh.gov.cn","zjjcmspublic.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"],
        "historicalEvidenceByYear":{
          "2024":["https://www.qdh.gov.cn/art/2024/4/3/art_1289606_59033591.html"],
          "2025":[],"2026":[],"2027":[]
        }
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
WHERE source.code='HZ_CHUNAN_GOV'
ON CONFLICT (source_id, recruitment_year) DO NOTHING;

UPDATE target_source_catalog target
SET recruitment_source_id=source.id,
    connection_status=CASE
        WHEN target.connection_status = 'CONNECTED' THEN 'CONNECTED'
        ELSE 'PARTIAL'
    END,
    official_root_url=source.base_uri, updated_at=now()
FROM recruitment_source source
WHERE target.code='HZ_CHUNAN_GOV' AND source.code=target.code;

INSERT INTO source_onboarding_checkpoint (source_id, checkpoint, status, evidence, verified_at)
SELECT source.id, checkpoint.value,
       CASE WHEN checkpoint.value='REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
       CASE checkpoint.value
         WHEN 'REGISTERED' THEN '淳安县政府 2024 统一招聘官方详情已登记；未发现可复现的现行列表 API，状态保持 PARTIAL'
         WHEN 'CONTRACT_VERIFIED' THEN '等待定位政府门户现行人员招聘叶栏目及分页契约'
         WHEN 'LIVE_SMOKE_VERIFIED' THEN '等待受控实网保存 2024 主公告、岗位附件及生命周期证据'
         WHEN 'BACKFILL_COMPLETE' THEN '2024-2026 连续归档未闭合；2025 镜像不得替代官方证据'
         ELSE '固定官方证据支持稳定 URL 去重；完整增量监控等待新栏目契约'
       END, now()
FROM recruitment_source source
CROSS JOIN (VALUES ('REGISTERED'),('CONTRACT_VERIFIED'),('LIVE_SMOKE_VERIFIED'),
    ('BACKFILL_COMPLETE'),('INCREMENTAL_VERIFIED')) AS checkpoint(value)
WHERE source.code='HZ_CHUNAN_GOV'
ON CONFLICT (source_id, checkpoint) DO NOTHING;
