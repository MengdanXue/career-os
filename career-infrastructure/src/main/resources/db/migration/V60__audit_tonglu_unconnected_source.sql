-- The former recruitment column is no longer available and no deterministic
-- official archive contract has been verified. Keep this target explicitly
-- unconnected instead of seeding a non-recruitment page as evidence.
UPDATE target_source_catalog
SET connection_status = CASE
        WHEN recruitment_source_id IS NULL THEN 'NOT_CONNECTED'
        ELSE connection_status
    END,
    official_root_url = 'https://www.tonglu.gov.cn/',
    updated_at = now()
WHERE code = 'HZ_TONGLU_GOV';
