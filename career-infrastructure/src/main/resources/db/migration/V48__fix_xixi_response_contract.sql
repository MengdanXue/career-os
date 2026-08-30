UPDATE recruitment_source
SET last_failure_at = NULL,
    consecutive_failure_count = 0,
    next_due_at = now(),
    updated_at = now()
WHERE code = 'HZ_XIXI_HOSPITAL';

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
    stop_reason = NULL,
    updated_at = now()
FROM recruitment_source source
WHERE coverage.source_id = source.id
  AND source.code = 'HZ_XIXI_HOSPITAL'
  AND coverage.recruitment_year BETWEEN 2024 AND 2027;

UPDATE target_source_catalog target
SET connection_status = 'PARTIAL',
    updated_at = now()
FROM recruitment_source source
WHERE target.code = 'HZ_XIXI_HOSPITAL'
  AND source.code = target.code;

UPDATE source_onboarding_checkpoint checkpoint
SET status = CASE WHEN checkpoint.checkpoint = 'REGISTERED' THEN 'VERIFIED' ELSE 'PENDING' END,
    evidence = CASE checkpoint.checkpoint
        WHEN 'REGISTERED' THEN '官网响应契约已修正：普通 HTML 请求不再错误携带 X-Requested-With；官网 Content-Type 与原始字节均按 UTF-8 处理；状态保持 PARTIAL'
        WHEN 'CONTRACT_VERIFIED' THEN '等待重新核对招聘公告和考试生命周期列表的分页、标题、日期与详情链接'
        WHEN 'LIVE_SMOKE_VERIFIED' THEN '等待按修正后的请求头和字符集保存受控实网证据'
        WHEN 'BACKFILL_COMPLETE' THEN '旧错误 AJAX 请求产生的覆盖证据已失效，等待 2024-2026 重新回填'
        ELSE '等待第二次无重复增量运行验证稳定岗位键、内容指纹和变化事件'
    END,
    verified_at = now()
FROM recruitment_source source
WHERE checkpoint.source_id = source.id
  AND source.code = 'HZ_XIXI_HOSPITAL';
