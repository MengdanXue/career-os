CREATE TABLE target_source_catalog (
    code VARCHAR(100) PRIMARY KEY,
    name VARCHAR(300) NOT NULL,
    route_code VARCHAR(64) NOT NULL,
    organization_type VARCHAR(64) NOT NULL,
    region VARCHAR(100) NOT NULL,
    authority_level VARCHAR(32) NOT NULL CHECK (authority_level IN ('OFFICIAL_AGGREGATOR', 'OFFICIAL_ORGANIZATION')),
    official_root_url TEXT NOT NULL,
    connection_status VARCHAR(32) NOT NULL CHECK (connection_status IN ('CONNECTED', 'PARTIAL', 'FAILED', 'NOT_CONNECTED')),
    recruitment_source_id UUID REFERENCES recruitment_source(id),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO target_source_catalog (
    code, name, route_code, organization_type, region, authority_level,
    official_root_url, connection_status, recruitment_source_id
)
SELECT target.code, target.name, target.route_code, target.organization_type, target.region,
       target.authority_level, target.official_root_url, target.connection_status, source.id
FROM (VALUES
    ('ZJ_HRSS_INSTITUTION', '浙江省人社厅事业单位招聘', 'PUBLIC_TECH', 'GOVERNMENT', '浙江', 'OFFICIAL_AGGREGATOR', 'https://rlsbt.zj.gov.cn/', 'CONNECTED'),
    ('HZ_HRSS_INSTITUTION', '杭州市人社局事业单位招聘', 'PUBLIC_TECH', 'GOVERNMENT', '杭州', 'OFFICIAL_AGGREGATOR', 'https://hrss.hangzhou.gov.cn/', 'CONNECTED'),
    ('HZ_BINJIANG_GOV', '杭州高新区（滨江）政府招聘', 'PUBLIC_TECH', 'GOVERNMENT', '杭州滨江', 'OFFICIAL_AGGREGATOR', 'https://www.hhtz.gov.cn/', 'NOT_CONNECTED'),
    ('HZ_YUHANG_GOV', '余杭区政府招聘', 'PUBLIC_TECH', 'GOVERNMENT', '杭州余杭', 'OFFICIAL_AGGREGATOR', 'https://www.yuhang.gov.cn/', 'NOT_CONNECTED'),
    ('HZ_QIANTANG_GOV', '钱塘区政府招聘', 'PUBLIC_TECH', 'GOVERNMENT', '杭州钱塘', 'OFFICIAL_AGGREGATOR', 'https://www.qiantang.gov.cn/', 'NOT_CONNECTED'),
    ('HZ_XIAOSHAN_GOV', '萧山区政府招聘', 'PUBLIC_TECH', 'GOVERNMENT', '杭州萧山', 'OFFICIAL_AGGREGATOR', 'https://www.xiaoshan.gov.cn/', 'NOT_CONNECTED'),
    ('HZ_LINPING_GOV', '临平区政府招聘', 'PUBLIC_TECH', 'GOVERNMENT', '杭州临平', 'OFFICIAL_AGGREGATOR', 'https://www.linping.gov.cn/', 'NOT_CONNECTED'),
    ('HDU_RECRUITMENT', '杭州电子科技大学招聘', 'UNIVERSITY_HOSPITAL_IT', 'UNIVERSITY', '杭州', 'OFFICIAL_ORGANIZATION', 'https://renshi.hdu.edu.cn/', 'NOT_CONNECTED'),
    ('ZJUT_RECRUITMENT', '浙江工业大学招聘', 'UNIVERSITY_HOSPITAL_IT', 'UNIVERSITY', '杭州', 'OFFICIAL_ORGANIZATION', 'https://www.zjut.edu.cn/', 'NOT_CONNECTED'),
    ('ZJGSU_RECRUITMENT', '浙江工商大学招聘', 'UNIVERSITY_HOSPITAL_IT', 'UNIVERSITY', '杭州', 'OFFICIAL_ORGANIZATION', 'https://www.zjgsu.edu.cn/', 'NOT_CONNECTED'),
    ('HZNU_RECRUITMENT', '杭州师范大学招聘', 'UNIVERSITY_HOSPITAL_IT', 'UNIVERSITY', '杭州', 'OFFICIAL_ORGANIZATION', 'https://www.hznu.edu.cn/', 'NOT_CONNECTED'),
    ('HZ_FIRST_HOSPITAL', '杭州市第一人民医院招聘', 'UNIVERSITY_HOSPITAL_IT', 'HOSPITAL', '杭州', 'OFFICIAL_ORGANIZATION', 'https://zp.hz-hospital.com/', 'NOT_CONNECTED'),
    ('HZ_CHILDRENS_HOSPITAL', '杭州市儿童医院招聘', 'UNIVERSITY_HOSPITAL_IT', 'HOSPITAL', '杭州', 'OFFICIAL_ORGANIZATION', 'https://www.hzch.org.cn/', 'NOT_CONNECTED'),
    ('HZ_XIXI_HOSPITAL', '杭州市西溪医院招聘', 'UNIVERSITY_HOSPITAL_IT', 'HOSPITAL', '杭州', 'OFFICIAL_ORGANIZATION', 'https://www.xixih.net/', 'NOT_CONNECTED'),
    ('HZ_TCM_HOSPITAL', '杭州市中医院招聘', 'UNIVERSITY_HOSPITAL_IT', 'HOSPITAL', '杭州', 'OFFICIAL_ORGANIZATION', 'https://www.hztcm.net/', 'NOT_CONNECTED'),
    ('UCAS_HANGZHOU', '国科大杭州高等研究院招聘', 'RESEARCH_SUPPORT', 'RESEARCH_INSTITUTE', '杭州', 'OFFICIAL_ORGANIZATION', 'https://hias.ucas.ac.cn/', 'NOT_CONNECTED'),
    ('WESTLAKE_RESEARCH', '西湖大学科研技术岗位', 'RESEARCH_SUPPORT', 'RESEARCH_INSTITUTE', '杭州', 'OFFICIAL_ORGANIZATION', 'https://www.westlake.edu.cn/', 'NOT_CONNECTED'),
    ('HZ_DATA_GROUP', '杭州数据集团招聘', 'GOVERNMENT_SOE_DIGITAL', 'STATE_OWNED_ENTERPRISE', '杭州', 'OFFICIAL_ORGANIZATION', 'https://hr.hzfi.cn/', 'NOT_CONNECTED'),
    ('HZ_CAPITAL_GROUP', '杭州市国有资本投资运营有限公司招聘', 'GOVERNMENT_SOE_DIGITAL', 'STATE_OWNED_ENTERPRISE', '杭州', 'OFFICIAL_ORGANIZATION', 'https://hr.hzfi.cn/', 'NOT_CONNECTED'),
    ('HZ_METRO_GROUP', '杭州地铁集团招聘', 'GOVERNMENT_SOE_DIGITAL', 'STATE_OWNED_ENTERPRISE', '杭州', 'OFFICIAL_ORGANIZATION', 'https://www.hzmetro.com/', 'NOT_CONNECTED')
) AS target(code, name, route_code, organization_type, region, authority_level, official_root_url, connection_status)
LEFT JOIN recruitment_source source ON source.code = target.code;

CREATE INDEX idx_target_source_catalog_route_status
    ON target_source_catalog(route_code, connection_status)
    WHERE enabled;
