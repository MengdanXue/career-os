UPDATE recruitment_source
SET entry_uri = 'https://renshi.hdu.edu.cn/13762/list.htm',
    updated_at = now()
WHERE code = 'HDU_RECRUITMENT';

INSERT INTO source_onboarding_checkpoint (
    source_id, checkpoint, status, evidence, verified_at
)
SELECT id, 'CONTRACT_VERIFIED', 'VERIFIED',
       '招聘信息入口 /13762/list.htm 与静态翻页 /13762/list2.htm 已通过官方站点核验', now()
FROM recruitment_source
WHERE code = 'HDU_RECRUITMENT'
ON CONFLICT (source_id, checkpoint) DO UPDATE SET
    status = EXCLUDED.status,
    evidence = EXCLUDED.evidence,
    verified_at = EXCLUDED.verified_at;
