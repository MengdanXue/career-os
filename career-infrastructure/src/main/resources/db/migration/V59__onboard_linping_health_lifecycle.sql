INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES (
    '01992f09-0000-7000-8000-000000000422',
    'HZ_LINPING_GOV', '临平区政府招聘',
    'https://www.linping.gov.cn/', 'https://www.linping.gov.cn/col/col1229597298/index.html',
    'OFFICIAL_GOVERNMENT', '杭州临平', 'STATIC_HTML', TRUE,
    '0 15 9 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "allowedHosts":["www.linping.gov.cn"],"attachmentSelector":"a[href]",
      "listingEntries":[{
        "code":"health-personnel-recruitment",
        "entryUri":"https://www.linping.gov.cn/col/col1229597298/index.html",
        "role":"SECTOR","mode":"JCMS_PARAM_JSON",
        "recruitmentYears":[2024,2025,2026,2027],
        "knownArchiveGapYears":[2024,2025,2026],"completenessRequired":true,
        "listingApiUri":"https://www.linping.gov.cn/api-gateway/jpaas-publish-server/front/page/build/unit?parseType=bulidstatic&webId=3765&tplSetId=RMLRv5FdPENr7kZUlVnuV&pageType=column&tagId=%E4%BF%A1%E6%81%AF%E5%88%97%E8%A1%A8&editType=null&pageId=1229597298",
        "historicalPageSize":15,"historicalMaxPages":8,"incrementalListingMaxPages":2,
        "jcmsSearch":{"xxgkId":"W001","xxgkType":"","className":"人事信息"},
        "reconcileReportedTotalByListingItems":true,
        "listingItemSelector":"#信息列表 .page-content ul.ajax-ul > li.cf.border-line",
        "itemLinkSelector":"a.fl[href]","itemTitleSelector":"a.fl[href]",
        "itemPublishedDateSelector":"span.fr","linkSelector":"a.fl[href]",
        "reportedTotalRegex":"count[^0-9]+(\\d+)",
        "reportedCurrentPageRegex":"pageNo[^0-9]+(\\d+)",
        "articleUrlRegex":"^https://www\\.linping\\.gov\\.cn/(?:col/col1229597298/)?art/[0-9]{4}(?:/[0-9]+/[0-9]+)?/art_[A-Za-z0-9_]+\\.html$",
        "titleIncludeRegex":"公开招聘|招聘事业编制|公开选聘|高层次|紧缺|应届|笔试|资格复审|面试|成绩|体检|考察|递补|拟聘用|公示|核减|取消",
        "titleExcludeRegex":"编外|劳务派遣|人才派遣|合同制|招聘会|培训|讲座"
      }]
    }'::jsonb, now()
)
ON CONFLICT (code) DO UPDATE SET
    name=EXCLUDED.name, base_uri=EXCLUDED.base_uri, entry_uri=EXCLUDED.entry_uri,
    source_type=EXCLUDED.source_type, region=EXCLUDED.region, crawl_mode=EXCLUDED.crawl_mode,
    enabled=EXCLUDED.enabled, cron_expression=EXCLUDED.cron_expression, time_zone=EXCLUDED.time_zone,
    minimum_request_interval_ms=EXCLUDED.minimum_request_interval_ms,
    configuration=EXCLUDED.configuration, next_due_at=EXCLUDED.next_due_at,
    last_failure_at=NULL, consecutive_failure_count=0, updated_at=now();

INSERT INTO source_year_coverage (source_id, recruitment_year, status, discovered_count,
    fetched_count, parsed_count, target_job_count, updated_at)
SELECT source.id, year.value, 'NOT_DISCOVERED', 0, 0, 0, 0, now()
FROM recruitment_source source CROSS JOIN generate_series(2024, 2027) AS year(value)
WHERE source.code='HZ_LINPING_GOV'
ON CONFLICT (source_id, recruitment_year) DO NOTHING;

UPDATE target_source_catalog target
SET recruitment_source_id=source.id,
    connection_status=CASE
        WHEN target.connection_status = 'CONNECTED' THEN 'CONNECTED'
        ELSE 'PARTIAL'
    END,
    official_root_url=source.base_uri, updated_at=now()
FROM recruitment_source source
WHERE target.code='HZ_LINPING_GOV' AND source.code=target.code;

INSERT INTO source_onboarding_checkpoint (source_id, checkpoint, status, evidence, verified_at)
SELECT source.id, checkpoint.value,
       CASE WHEN checkpoint.value='REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
       CASE checkpoint.value
         WHEN 'REGISTERED' THEN '临平卫健系统人员考录官方栏目已登记；仅覆盖卫健主管部门，状态保持 PARTIAL'
         WHEN 'CONTRACT_VERIFIED' THEN '等待机器核对 70 条/5 页、招聘到拟聘生命周期及编外排除规则'
         WHEN 'LIVE_SMOKE_VERIFIED' THEN '等待受控实网采集保存列表、详情、附件和岗位表证据'
         WHEN 'BACKFILL_COMPLETE' THEN '2024-2026 仅有卫健分部门覆盖，等待区人社、教育及其他主管部门来源补齐'
         ELSE '等待第二次无重复增量运行验证稳定键、内容指纹和变化事件'
       END, now()
FROM recruitment_source source
CROSS JOIN (VALUES ('REGISTERED'),('CONTRACT_VERIFIED'),('LIVE_SMOKE_VERIFIED'),
    ('BACKFILL_COMPLETE'),('INCREMENTAL_VERIFIED')) AS checkpoint(value)
WHERE source.code='HZ_LINPING_GOV'
ON CONFLICT (source_id, checkpoint) DO NOTHING;
