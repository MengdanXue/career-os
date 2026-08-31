UPDATE recruitment_source
SET base_uri = 'https://www.qdh.gov.cn/',
    entry_uri = 'https://www.qdh.gov.cn/col/col1289604/index.html',
    source_type = 'OFFICIAL_GOVERNMENT',
    crawl_mode = 'STATIC_HTML',
    configuration = '{
      "historicalYears":[2024,2025,2026,2027],
      "allowedHosts":["www.qdh.gov.cn","zjjcmspublic.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"],
      "attachmentSelector":"a[href]",
      "listingEntries":[{
        "code":"county-notice-lifecycle",
        "entryUri":"https://www.qdh.gov.cn/col/col1289604/index.html",
        "role":"PRIMARY","mode":"JCMS_PARAM_JSON",
        "recruitmentYears":[2024,2025,2026,2027],"completenessRequired":true,
        "listingApiUri":"https://www.qdh.gov.cn/api-gateway/jpaas-publish-server/front/page/build/unit?parseType=bulidstatic&webId=2241&tplSetId=amBdbt7RF5hHvSEVeduPl&pageType=column&tagId=%E6%96%87%E7%AB%A0%E5%88%97%E8%A1%A8&editType=null&pageId=1289604",
        "historicalPageSize":20,"historicalMaxPages":120,"incrementalListingMaxPages":2,
        "reconcileReportedTotalByListingItems":true,
        "allowedHosts":["www.qdh.gov.cn","zjjcmspublic.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"],
        "articleUrlRegex":"^https://www\\.qdh\\.gov\\.cn/(?:col/col[0-9]+/)?art/[0-9]{4}(?:/[0-9]+/[0-9]+)?/art_[A-Za-z0-9_]+\\.html$",
        "listingItemSelector":".page-content li","itemLinkSelector":"a[href]",
        "itemTitleSelector":"a[href]","itemPublishedDateSelector":".time","linkSelector":"a[href]",
        "reportedTotalRegex":"count=\\\\\"(\\d+)\\\\\"",
        "reportedCurrentPageRegex":"pageNo=\\\\\"(\\d+)\\\\\"",
        "titleIncludeRegex":"招聘|招考|招录|选聘|引进|人才|报名|岗位|笔试|考试|资格|复审|成绩|面试|体检|考察|公示|录用|聘用|递补|取消|核减",
        "titleExcludeRegex":"招聘会|培训|讲座|编外|派遣|临时用工"
      }]
    }'::jsonb,
    last_failure_at = NULL,
    consecutive_failure_count = 0,
    updated_at = now()
WHERE code = 'HZ_CHUNAN_GOV';

UPDATE recruitment_source
SET name = '杭州市儿童医院（市卫健委官方招聘兜底）',
    base_uri = 'https://wsjkw.hangzhou.gov.cn/',
    entry_uri = 'https://wsjkw.hangzhou.gov.cn/col/col1229318903/index.html?number=C011401',
    source_type = 'OFFICIAL_GOVERNMENT',
    crawl_mode = 'STATIC_HTML',
    configuration = '{
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
    last_failure_at = NULL,
    consecutive_failure_count = 0,
    updated_at = now()
WHERE code = 'HZ_CHILDRENS_HOSPITAL';

UPDATE source_onboarding_checkpoint checkpoint
SET evidence = CASE
        WHEN source.code = 'HZ_CHUNAN_GOV'
            THEN '旧固定公告失效；已切换淳安县政府通知公告 JCMS，覆盖招聘公告及后续生命周期'
        ELSE '儿童医院独立招聘系统持续返回 504；采用杭州市卫健委官方招聘与拟聘公示栏目兜底，保留单位官网标识'
    END,
    status = CASE WHEN checkpoint.checkpoint = 'REGISTERED' THEN 'VERIFIED' ELSE checkpoint.status END,
    verified_at = now()
FROM recruitment_source source
WHERE checkpoint.source_id = source.id
  AND source.code IN ('HZ_CHUNAN_GOV', 'HZ_CHILDRENS_HOSPITAL')
  AND checkpoint.checkpoint = 'REGISTERED';
