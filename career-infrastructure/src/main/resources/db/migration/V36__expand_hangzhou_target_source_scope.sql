ALTER TABLE target_source_catalog
    ADD COLUMN scope_level varchar(32) NOT NULL DEFAULT 'CITY',
    ADD COLUMN scope_code varchar(80) NOT NULL DEFAULT 'HANGZHOU',
    ADD COLUMN priority_tier varchar(8) NOT NULL DEFAULT 'P0',
    ADD COLUMN coverage_role varchar(32) NOT NULL DEFAULT 'PRIMARY';

ALTER TABLE target_source_catalog
    ADD CONSTRAINT ck_target_source_scope_level
        CHECK (scope_level IN ('CITY', 'DISTRICT', 'ORGANIZATION')),
    ADD CONSTRAINT ck_target_source_priority_tier
        CHECK (priority_tier IN ('P0', 'P1', 'P2')),
    ADD CONSTRAINT ck_target_source_coverage_role
        CHECK (coverage_role IN ('PRIMARY', 'SUPPLEMENTAL', 'DISCOVERY'));

INSERT INTO target_source_catalog (
    code, name, route_code, organization_type, region, authority_level,
    official_root_url, connection_status, recruitment_source_id, enabled,
    scope_level, scope_code, priority_tier, coverage_role, updated_at
)
SELECT code, name, 'PUBLIC_TECH', 'GOVERNMENT', region, 'OFFICIAL_AGGREGATOR',
       official_root_url, 'NOT_CONNECTED', NULL, true,
       'DISTRICT', scope_code, priority_tier, coverage_role, now()
FROM (VALUES
    ('HZ_SHANGCHENG_GOV', '上城区政府招聘', '杭州上城', 'https://www.hzsc.gov.cn/', 'HANGZHOU_SHANGCHENG', 'P0', 'PRIMARY'),
    ('HZ_GONGSHU_GOV', '拱墅区政府招聘', '杭州拱墅', 'https://www.gongshu.gov.cn/', 'HANGZHOU_GONGSHU', 'P0', 'PRIMARY'),
    ('HZ_XIHU_GOV', '西湖区政府招聘', '杭州西湖', 'https://www.hzxh.gov.cn/', 'HANGZHOU_XIHU', 'P0', 'PRIMARY'),
    ('HZ_FUYANG_GOV', '富阳区政府招聘', '杭州富阳', 'https://www.fuyang.gov.cn/', 'HANGZHOU_FUYANG', 'P0', 'PRIMARY'),
    ('HZ_LINAN_GOV', '临安区政府招聘', '杭州临安', 'https://www.linan.gov.cn/', 'HANGZHOU_LINAN', 'P0', 'PRIMARY'),
    ('HZ_JIANDE_GOV', '建德市政府招聘', '杭州建德', 'https://www.jiande.gov.cn/', 'HANGZHOU_JIANDE', 'P2', 'SUPPLEMENTAL'),
    ('HZ_TONGLU_GOV', '桐庐县政府招聘', '杭州桐庐', 'https://www.tonglu.gov.cn/', 'HANGZHOU_TONGLU', 'P2', 'SUPPLEMENTAL'),
    ('HZ_CHUNAN_GOV', '淳安县政府招聘', '杭州淳安', 'https://www.qdh.gov.cn/', 'HANGZHOU_CHUNAN', 'P2', 'SUPPLEMENTAL')
) AS value(code, name, region, official_root_url, scope_code, priority_tier, coverage_role)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    region = EXCLUDED.region,
    official_root_url = EXCLUDED.official_root_url,
    scope_level = EXCLUDED.scope_level,
    scope_code = EXCLUDED.scope_code,
    priority_tier = EXCLUDED.priority_tier,
    coverage_role = EXCLUDED.coverage_role,
    updated_at = now();

UPDATE target_source_catalog
SET scope_level = 'DISTRICT',
    scope_code = CASE code
        WHEN 'HZ_BINJIANG_GOV' THEN 'HANGZHOU_BINJIANG'
        WHEN 'HZ_YUHANG_GOV' THEN 'HANGZHOU_YUHANG'
        WHEN 'HZ_QIANTANG_GOV' THEN 'HANGZHOU_QIANTANG'
        WHEN 'HZ_XIAOSHAN_GOV' THEN 'HANGZHOU_XIAOSHAN'
        WHEN 'HZ_LINPING_GOV' THEN 'HANGZHOU_LINPING'
    END,
    priority_tier = 'P0',
    coverage_role = 'PRIMARY',
    updated_at = now()
WHERE code IN ('HZ_BINJIANG_GOV', 'HZ_YUHANG_GOV', 'HZ_QIANTANG_GOV', 'HZ_XIAOSHAN_GOV', 'HZ_LINPING_GOV');

UPDATE target_source_catalog
SET scope_level = 'ORGANIZATION',
    scope_code = code,
    coverage_role = 'PRIMARY',
    updated_at = now()
WHERE organization_type <> 'GOVERNMENT';
