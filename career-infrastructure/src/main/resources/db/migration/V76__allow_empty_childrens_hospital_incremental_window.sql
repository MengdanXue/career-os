UPDATE recruitment_source
SET configuration = configuration || jsonb_build_object('allowEmptyIncremental', true),
    updated_at = now()
WHERE code = 'HZ_CHILDRENS_HOSPITAL';

UPDATE source_onboarding_checkpoint checkpoint
SET evidence = evidence || '；医院专属标题在最近增量窗口中可合法为空，历史完整性仍由全量回填单独验证',
    verified_at = now()
FROM recruitment_source source
WHERE checkpoint.source_id = source.id
  AND source.code = 'HZ_CHILDRENS_HOSPITAL'
  AND checkpoint.checkpoint = 'REGISTERED'
  AND checkpoint.evidence NOT LIKE '%最近增量窗口中可合法为空%';
