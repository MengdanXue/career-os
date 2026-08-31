UPDATE recruitment_source
SET base_uri = 'https://www.zjut.edu.cn/',
    entry_uri = 'https://www.zjut.edu.cn/',
    updated_at = now()
WHERE code = 'ZJUT_RECRUITMENT';

UPDATE target_source_catalog
SET official_root_url = 'https://www.zjut.edu.cn/',
    updated_at = now()
WHERE code = 'ZJUT_RECRUITMENT';
