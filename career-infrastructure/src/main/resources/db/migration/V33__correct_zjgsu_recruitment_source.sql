UPDATE recruitment_source
SET base_uri = 'https://rsc.zjgsu.edu.cn/',
    entry_uri = 'https://rsc.zjgsu.edu.cn/938/list.htm',
    configuration = configuration || jsonb_build_object(
        'articleUrlRegex', '^https://rsc\.zjgsu\.edu\.cn/[0-9]{4}/[0-9]{4}/c[0-9]+a[0-9]+/page\.htm$'
    ),
    updated_at = now()
WHERE code = 'ZJGSU_RECRUITMENT';

UPDATE target_source_catalog
SET official_root_url = 'https://rsc.zjgsu.edu.cn/',
    updated_at = now()
WHERE code = 'ZJGSU_RECRUITMENT';

INSERT INTO source_onboarding_checkpoint (
    source_id, checkpoint, status, evidence, verified_at
)
SELECT id, 'CONTRACT_VERIFIED', 'VERIFIED',
       '人事处招聘信息入口 /938/list.htm 及同域文章 c938 已通过官方站点核验', now()
FROM recruitment_source
WHERE code = 'ZJGSU_RECRUITMENT'
ON CONFLICT (source_id, checkpoint) DO UPDATE SET
    status = EXCLUDED.status,
    evidence = EXCLUDED.evidence,
    verified_at = EXCLUDED.verified_at;
