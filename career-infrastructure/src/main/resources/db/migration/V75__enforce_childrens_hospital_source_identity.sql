UPDATE recruitment_source
SET name = '杭州市儿童医院招聘（市卫健委官方镜像）',
    configuration = jsonb_set(
        jsonb_set(
            configuration,
            '{listingEntries,0,titleIncludeRegex}',
            to_jsonb('(?=.*(?:杭州市儿童医院|杭州儿童医院|市儿童医院))(?=.*(?:招聘|招考|招录|选聘|引进|人才|报名|岗位|笔试|考试|资格|复审|成绩|面试|体检|考察|公示|录用|聘用|递补|取消|核减)).*'::text)
        ),
        '{listingEntries,1,titleIncludeRegex}',
        to_jsonb('(?=.*(?:杭州市儿童医院|杭州儿童医院|市儿童医院))(?=.*(?:招聘|招考|招录|选聘|引进|人才|资格|复审|成绩|面试|体检|考察|公示|录用|聘用|递补|取消|核减)).*'::text)
    ),
    last_failure_at = NULL,
    consecutive_failure_count = 0,
    updated_at = now()
WHERE code = 'HZ_CHILDRENS_HOSPITAL';

-- V73 temporarily acquired the full citywide Health Commission feed under the
-- hospital identity. Retire that contaminated snapshot. A subsequent run can
-- reactivate only notices whose listing title explicitly names the hospital.
UPDATE acquired_document document
SET document_state = 'DEACTIVATED',
    last_gone_at = COALESCE(document.last_gone_at, now()),
    consecutive_gone_count = GREATEST(document.consecutive_gone_count, 2),
    version = document.version + 1
FROM recruitment_source source
WHERE document.source_id = source.id
  AND source.code = 'HZ_CHILDRENS_HOSPITAL'
  AND document.document_state = 'ACTIVE';

UPDATE source_year_coverage coverage
SET status = 'NOT_DISCOVERED',
    discovered_count = 0,
    fetched_count = 0,
    parsed_count = 0,
    target_job_count = 0,
    completion_basis = NULL,
    completed_at = NULL,
    listing_page_count = 0,
    filtered_count = 0,
    failed_count = 0,
    earliest_published_on = NULL,
    latest_published_on = NULL,
    stop_reason = 'SOURCE_IDENTITY_FILTER_CHANGED',
    updated_at = now()
FROM recruitment_source source
WHERE coverage.source_id = source.id
  AND source.code = 'HZ_CHILDRENS_HOSPITAL';

UPDATE source_onboarding_checkpoint checkpoint
SET evidence = '儿童医院独立招聘系统持续返回 504；市卫健委镜像仅接收标题明确包含杭州市儿童医院的公告，已停用此前误收的全市卫健委文档',
    status = CASE WHEN checkpoint.checkpoint = 'REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
    verified_at = now()
FROM recruitment_source source
WHERE checkpoint.source_id = source.id
  AND source.code = 'HZ_CHILDRENS_HOSPITAL';

UPDATE target_source_catalog
SET connection_status = 'PARTIAL',
    updated_at = now()
WHERE code = 'HZ_CHILDRENS_HOSPITAL';
