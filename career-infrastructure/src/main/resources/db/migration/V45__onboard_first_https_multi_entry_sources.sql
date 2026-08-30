INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES
(
    '01992f09-0000-7000-8000-000000000409',
    'HZ_TCM_HOSPITAL', '杭州市中医院招聘',
    'https://www.hztcm.net/', 'https://hztcm.unitedsoft.cn/News130a1.htm',
    'OFFICIAL_ORGANIZATION', '杭州', 'STATIC_HTML', TRUE,
    '0 35 10 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "allowedHosts":["hztcm.unitedsoft.cn"],
      "attachmentSelector":"a[href]",
      "listingEntries":[
        {
          "code":"recruitment","entryUri":"https://hztcm.unitedsoft.cn/News130a1.htm",
          "role":"PRIMARY","mode":"STATIC_SUFFIX_TEMPLATE",
          "recruitmentYears":[2024,2025,2026,2027],"completenessRequired":true,
          "pageUriTemplate":"https://hztcm.unitedsoft.cn/News130a{page}.htm",
          "historicalMaxPages":20,"incrementalListingMaxPages":2,
          "allowedHosts":["hztcm.unitedsoft.cn"],
          "articleUrlRegex":"^https://hztcm\\.unitedsoft\\.cn/View[0-9]+a130\\.htm$",
          "listingItemSelector":"table.listtab tr[class^=listtab]",
          "itemLinkSelector":"a[href]","itemTitleSelector":"a[href]",
          "itemPublishedDateSelector":"td:last-child",
          "reportedTotalRegex":"共\\s*<font[^>]*>\\s*<b>(\\d+)</b>",
          "reportedTotalPagesRegex":"当前页:\\s*<font[^>]*>\\s*<b>\\d+/(\\d+)</b>",
          "reportedCurrentPageRegex":"当前页:\\s*<font[^>]*>\\s*<b>(\\d+)/\\d+</b>",
          "titleIncludeRegex":"招聘|招考|选聘|引进|人才",
          "titleExcludeRegex":"招聘会|培训|讲座"
        },
        {
          "code":"exam-lifecycle","entryUri":"https://hztcm.unitedsoft.cn/News135a1.htm",
          "role":"LIFECYCLE","mode":"STATIC_SUFFIX_TEMPLATE",
          "recruitmentYears":[2024,2025,2026,2027],"completenessRequired":false,
          "pageUriTemplate":"https://hztcm.unitedsoft.cn/News135a{page}.htm",
          "historicalMaxPages":20,"incrementalListingMaxPages":2,
          "allowedHosts":["hztcm.unitedsoft.cn"],
          "articleUrlRegex":"^https://hztcm\\.unitedsoft\\.cn/View[0-9]+a135\\.htm$",
          "listingItemSelector":"table.listtab tr[class^=listtab]",
          "itemLinkSelector":"a[href]","itemTitleSelector":"a[href]",
          "itemPublishedDateSelector":"td:last-child",
          "reportedTotalRegex":"共\\s*<font[^>]*>\\s*<b>(\\d+)</b>",
          "reportedTotalPagesRegex":"当前页:\\s*<font[^>]*>\\s*<b>\\d+/(\\d+)</b>",
          "reportedCurrentPageRegex":"当前页:\\s*<font[^>]*>\\s*<b>(\\d+)/\\d+</b>",
          "titleIncludeRegex":"笔试|考试安排|专业知识测试|实践技能测试|面试|资格复审|资格审查|成绩|体检|考察|公示|录用|拟录用|聘用|递补",
          "titleExcludeRegex":"招聘会|培训|讲座"
        }
      ]
    }'::jsonb,
    now()
),
(
    '01992f09-0000-7000-8000-000000000410',
    'HZ_XIXI_HOSPITAL', '杭州市西溪医院招聘',
    'https://www.xixih.net/', 'https://zp.hzxixih.cn/index/index/announcement.html?page=1',
    'OFFICIAL_ORGANIZATION', '杭州', 'STATIC_HTML', TRUE,
    '0 50 10 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "allowedHosts":["zp.hzxixih.cn"],
      "attachmentSelector":"a[href]",
      "imageEvidenceSelector":".content img[src*=''/ueditor/php/upload/image/'']",
      "listingEntries":[
        {
          "code":"announcements","entryUri":"https://zp.hzxixih.cn/index/index/announcement.html?page=1",
          "role":"PRIMARY","mode":"QUERY_PAGE",
          "recruitmentYears":[2024,2025,2026,2027],"completenessRequired":true,
          "pageParameter":"page","historicalMaxPages":14,"incrementalListingMaxPages":2,
          "emptyPageConfirmationPages":0,"allowedHosts":["zp.hzxixih.cn"],
          "articleUrlRegex":"^https://zp\\.hzxixih\\.cn/index/index/announcement_desc/id/[0-9]+\\.html$",
          "listingItemSelector":".ul-imgtxt1 > li","itemLinkSelector":"h3 a[href]",
          "itemTitleSelector":"h3 a[href]","itemPublishedDateSelector":".date.date2",
          "titleIncludeRegex":"招聘|招考|选聘|引进|人才",
          "titleExcludeRegex":"招聘会|培训|讲座"
        },
        {
          "code":"exam-lifecycle","entryUri":"https://zp.hzxixih.cn/index/index/news.html?page=1",
          "role":"LIFECYCLE","mode":"QUERY_PAGE",
          "recruitmentYears":[2024,2025,2026,2027],"completenessRequired":false,
          "pageParameter":"page","historicalMaxPages":15,"incrementalListingMaxPages":2,
          "emptyPageConfirmationPages":0,"allowedHosts":["zp.hzxixih.cn"],
          "articleUrlRegex":"^https://zp\\.hzxixih\\.cn/index/index/announcement_desc/id/[0-9]+\\.html$",
          "listingItemSelector":".ul-imgtxt1 > li","itemLinkSelector":"h3 a[href]",
          "itemTitleSelector":"h3 a[href]","itemPublishedDateSelector":".date.date2",
          "titleIncludeRegex":"笔试|考试安排|专业知识测试|实践技能测试|面试|资格复审|资格审查|成绩|体检|考察|公示|录用|拟录用|聘用|递补",
          "titleExcludeRegex":"招聘会|培训|讲座"
        }
      ]
    }'::jsonb,
    now()
),
(
    '01992f09-0000-7000-8000-000000000411',
    'HZ_DATA_GROUP', '杭州数据集团招聘',
    'https://hr.hzfi.cn/', 'https://hr.hzfi.cn/jsp/recruit/recruit_news_list.jsp?colId=5235',
    'OFFICIAL_SOE', '杭州', 'STATIC_HTML', TRUE,
    '0 5 11 * * *', 'Asia/Shanghai', 1500,
    '{
      "historicalYears":[2024,2025,2026,2027],
      "allowedHosts":["hr.hzfi.cn"],
      "attachmentSelector":"a[href]",
      "listingEntries":[
        {
          "code":"social-recruitment",
          "entryUri":"https://hr.hzfi.cn/jsp/recruit/recruit_news_list.jsp?colId=5235",
          "role":"PRIMARY","mode":"QUERY_PAGE",
          "recruitmentYears":[2024,2025,2026,2027],"completenessRequired":true,
          "pageParameter":"page","historicalMaxPages":20,"incrementalListingMaxPages":2,
          "allowedHosts":["hr.hzfi.cn"],
          "articleUrlRegex":"^https://hr\\.hzfi\\.cn/jsp/info/news_info\\.jsp\\?infoId=H@[A-Za-z0-9]+$",
          "listingItemSelector":".list .intro","itemLinkSelector":".header[data-id]",
          "itemTitleSelector":".header[data-id]","itemPublishedDateSelector":".pubDate",
          "itemUriAttribute":"data-id","itemUriTemplate":"/jsp/info/news_info.jsp?infoId={value}",
          "reportedTotalRegex":"var\\s+recordNum\\s*=\\s*(\\d+)",
          "reportedTotalPagesRegex":"var\\s+totalPage\\s*=\\s*(\\d+)",
          "reportedCurrentPageRegex":"var\\s+curPage\\s*=\\s*(\\d+)",
          "titleIncludeRegex":"招聘|招考|选聘|引进|人才|笔试|考试安排|面试|资格复审|资格审查|成绩|体检|考察|公示|录用|拟录用|聘用|递补",
          "titleExcludeRegex":"招聘会|校园宣讲|培训|讲座"
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
WHERE source.code IN ('HZ_TCM_HOSPITAL', 'HZ_XIXI_HOSPITAL', 'HZ_DATA_GROUP')
ON CONFLICT (source_id, recruitment_year) DO NOTHING;

UPDATE target_source_catalog target
SET recruitment_source_id = source.id,
    connection_status = 'PARTIAL',
    official_root_url = source.base_uri,
    updated_at = now()
FROM recruitment_source source
WHERE target.code = source.code
  AND source.code IN ('HZ_TCM_HOSPITAL', 'HZ_XIXI_HOSPITAL', 'HZ_DATA_GROUP');

INSERT INTO source_onboarding_checkpoint (
    source_id, checkpoint, status, evidence, verified_at
)
SELECT source.id,
       checkpoint.value,
       CASE WHEN checkpoint.value = 'REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
       CASE checkpoint.value
           WHEN 'REGISTERED' THEN '官方 HTTPS 列表、详情模板、分页边界和附件范围已登记；连接状态保持 PARTIAL 直至实际回填验收'
           WHEN 'CONTRACT_VERIFIED' THEN '等待采集运行对官方报告总数、末页和唯一详情链接进行机器核对'
           WHEN 'LIVE_SMOKE_VERIFIED' THEN '等待受控实时采集成功并保存原始证据'
           WHEN 'BACKFILL_COMPLETE' THEN '等待 2024-2027 回填；图片岗位表必须完成 OCR 后才可完整'
           ELSE '等待第二次无重复增量运行验证稳定岗位键、内容指纹和差异事件'
       END,
       now()
FROM recruitment_source source
CROSS JOIN (VALUES
    ('REGISTERED'), ('CONTRACT_VERIFIED'), ('LIVE_SMOKE_VERIFIED'),
    ('BACKFILL_COMPLETE'), ('INCREMENTAL_VERIFIED')
) AS checkpoint(value)
WHERE source.code IN ('HZ_TCM_HOSPITAL', 'HZ_XIXI_HOSPITAL', 'HZ_DATA_GROUP')
ON CONFLICT (source_id, checkpoint) DO UPDATE SET
    status = EXCLUDED.status,
    evidence = EXCLUDED.evidence,
    verified_at = EXCLUDED.verified_at;
