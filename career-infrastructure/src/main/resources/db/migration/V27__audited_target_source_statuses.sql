UPDATE target_source_catalog
SET official_root_url = 'https://talents.zjgsu.edu.cn/',
    connection_status = 'PARTIAL',
    updated_at = now()
WHERE code = 'ZJGSU_RECRUITMENT';

UPDATE target_source_catalog
SET official_root_url = 'https://rsc.hznu.edu.cn/',
    connection_status = 'NOT_CONNECTED',
    updated_at = now()
WHERE code = 'HZNU_RECRUITMENT';
