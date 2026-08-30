INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES (
    '01992f09-0000-7000-8000-000000000421',
    'HZ_BINJIANG_GOV', '杭州高新区（滨江）政府招聘',
    'https://www.hhtz.gov.cn/', 'https://www.hhtz.gov.cn/col/col1487002/index.html',
    'OFFICIAL_GOVERNMENT', '杭州滨江', 'STATIC_HTML', TRUE,
    '0 5 9 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "allowedHosts":["www.hhtz.gov.cn"],"attachmentSelector":"a[href]",
      "listingEntries":[{
        "code":"general-notices-recruitment-lifecycle",
        "entryUri":"https://www.hhtz.gov.cn/col/col1487002/index.html",
        "role":"SECTOR","mode":"STATIC_SUFFIX_TEMPLATE",
        "recruitmentYears":[2024,2025,2026,2027],
        "knownArchiveGapYears":[2024,2025,2026],"completenessRequired":true,
        "pageUriTemplate":"https://www.hhtz.gov.cn/api-gateway/jpaas-publish-server/front/page/build/unit?parseType=bulidstatic&webId=2945&tplSetId=quSyqDUrFfjv1nGRQhEEH&pageType=column&tagId=%E9%98%B3%E5%85%89%E6%94%BF%E5%8A%A1_%E5%88%86%E9%A1%B5%E5%88%97%E8%A1%A8&editType=null&pageId=1487002&paramJson=%7B%22pageNo%22%3A{page}%2C%22pageSize%22%3A50%7D",
        "historicalPageSize":50,"historicalMaxPages":25,"incrementalListingMaxPages":2,
        "reconcileReportedTotalByListingItems":true,
        "listingItemSelector":"#阳光政务_分页列表 .page-content > li",
        "itemLinkSelector":"a.news[href]","itemTitleSelector":"a.news[href]",
        "itemPublishedDateSelector":"span.time","linkSelector":"a.news[href]",
        "reportedTotalRegex":"count[^0-9]+(\\d+)",
        "reportedCurrentPageRegex":"pageNo[^0-9]+(\\d+)",
        "articleUrlRegex":"^https://www\\.hhtz\\.gov\\.cn/(?:col/col1487002/)?art/[0-9]{4}(?:/[0-9]+/[0-9]+)?/art_[A-Za-z0-9_]+\\.html$",
        "titleIncludeRegex":"公开招聘|直接考核招聘|选聘|笔试|资格复审|面试|体检|考察|递补|拟聘用|公示|核减|取消",
        "titleExcludeRegex":"编外|合同制|劳务派遣|人才派遣|社工|聘用制教师|招聘会|培训|讲座"
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
WHERE source.code='HZ_BINJIANG_GOV'
ON CONFLICT (source_id, recruitment_year) DO NOTHING;

UPDATE target_source_catalog target
SET recruitment_source_id=source.id,
    connection_status=CASE
        WHEN target.connection_status = 'CONNECTED' THEN 'CONNECTED'
        ELSE 'PARTIAL'
    END,
    official_root_url=source.base_uri, updated_at=now()
FROM recruitment_source source
WHERE target.code='HZ_BINJIANG_GOV' AND source.code=target.code;

INSERT INTO source_onboarding_checkpoint (source_id, checkpoint, status, evidence, verified_at)
SELECT source.id, checkpoint.value,
       CASE WHEN checkpoint.value='REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
       CASE checkpoint.value
         WHEN 'REGISTERED' THEN '滨江官方通知总表已登记；事业编历史栏目分散且旧栏目失效，状态保持 PARTIAL'
         WHEN 'CONTRACT_VERIFIED' THEN '等待机器核对官方通知总表 931 条/19 页及合同制、社工、聘用制教师排除规则'
         WHEN 'LIVE_SMOKE_VERIFIED' THEN '等待受控实网采集保存招聘与生命周期详情证据'
         WHEN 'BACKFILL_COMPLETE' THEN '2024-2026 事业编主栏目存在已核实档案缺口，等待主管部门来源补齐'
         ELSE '等待第二次无重复增量运行验证稳定键、内容指纹和变化事件'
       END, now()
FROM recruitment_source source
CROSS JOIN (VALUES ('REGISTERED'),('CONTRACT_VERIFIED'),('LIVE_SMOKE_VERIFIED'),
    ('BACKFILL_COMPLETE'),('INCREMENTAL_VERIFIED')) AS checkpoint(value)
WHERE source.code='HZ_BINJIANG_GOV'
ON CONFLICT (source_id, checkpoint) DO NOTHING;
