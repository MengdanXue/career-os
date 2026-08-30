INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES (
    '01992f09-0000-7000-8000-000000000425',
    'HZ_CHILDRENS_HOSPITAL', '杭州市儿童医院招聘',
    'https://rs.hzch.org/', 'https://rs.hzch.org/apply/getMore.action?pageNumber=1',
    'OFFICIAL_ORGANIZATION', '浙江杭州', 'STATIC_HTML', TRUE,
    '0 45 9 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "allowedHosts":["rs.hzch.org"],
      "allowedPathPrefixes":["/apply/","/file_zp/attached/"],
      "attachmentSelector":"a[href]",
      "imageEvidenceSelector":"img[src*=''file_zp/attached/''], img[src*=''downloadAccessory.action'']",
      "listingEntries":[{
        "code":"current-recruitment-lifecycle",
        "entryUri":"https://rs.hzch.org/apply/getMore.action?pageNumber=1",
        "role":"CAMPAIGN_STATE","mode":"CAMPAIGN_STATE",
        "recruitmentYears":[2024,2025,2026,2027],
        "knownArchiveGapYears":[2024,2025],"completenessRequired":false,
        "allowedHosts":["rs.hzch.org"],
        "allowedPathPrefixes":["/apply/","/file_zp/attached/"],
        "linkSelector":"a[href*=''getNotice.action?keycode='']",
        "articleUrlRegex":"^https://rs\\.hzch\\.org/apply/getNotice\\.action\\?keycode=[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$",
        "titleIncludeRegex":"招聘|招考|引进|简历|截止|笔试|实践技能|面试|资格|复审|体检|考察|公示|录用|聘用|递补|成绩|考试|通知",
        "titleExcludeRegex":"招聘会|技术支持|岗位培训|编外|劳务派遣"
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
WHERE source.code='HZ_CHILDRENS_HOSPITAL'
ON CONFLICT (source_id, recruitment_year) DO NOTHING;

UPDATE target_source_catalog target
SET recruitment_source_id=source.id,
    connection_status=CASE
        WHEN target.connection_status = 'CONNECTED' THEN 'CONNECTED'
        ELSE 'PARTIAL'
    END,
    official_root_url=source.base_uri, updated_at=now()
FROM recruitment_source source
WHERE target.code='HZ_CHILDRENS_HOSPITAL' AND source.code=target.code;

INSERT INTO source_onboarding_checkpoint (source_id, checkpoint, status, evidence, verified_at)
SELECT source.id, checkpoint.value,
       CASE WHEN checkpoint.value='REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
       CASE checkpoint.value
         WHEN 'REGISTERED' THEN '儿童医院独立官方招聘系统当前公告、考试、资格复审、截止和成绩入口已登记；状态保持 PARTIAL'
         WHEN 'CONTRACT_VERIFIED' THEN '等待机器复核当前招聘系统链接选择器、图片岗位表和附件范围'
         WHEN 'LIVE_SMOKE_VERIFIED' THEN '等待官网网关恢复稳定后受控实网保存列表、详情和图片证据'
         WHEN 'BACKFILL_COMPLETE' THEN '当前列表仅展示 2026 活动；2024-2025 由卫健委与人社局来源交叉补齐后再验收'
         ELSE '等待第二次无重复增量运行验证通知 URL、内容指纹和生命周期变化事件'
       END, now()
FROM recruitment_source source
CROSS JOIN (VALUES ('REGISTERED'),('CONTRACT_VERIFIED'),('LIVE_SMOKE_VERIFIED'),
    ('BACKFILL_COMPLETE'),('INCREMENTAL_VERIFIED')) AS checkpoint(value)
WHERE source.code='HZ_CHILDRENS_HOSPITAL'
ON CONFLICT (source_id, checkpoint) DO NOTHING;
