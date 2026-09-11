-- 应届身份的候选人侧事实（产品需求 §2.2）。
--
-- 公告限定“未落实工作单位”或“无社保缴纳记录”时，要求的是报名当天的状态，而报名还没发生。
-- 候选人今天无法确认一个未来时点的事实，只能声明一个打算，所以这两列存的是声明而不是事实：
-- DECLARED_MET 只能得出条件式结论，DECLARED_NOT_MET 才是明确不可报，UNDECLARED 是待确认。
--
-- 默认 UNDECLARED：基线 §2.2 把这两项列为“不得由模型猜测”的字段，缺省必须是“没说”，
-- 不能是“满足”。

ALTER TABLE candidate_profile
    ADD COLUMN employer_settlement_at_application VARCHAR(32) NOT NULL DEFAULT 'UNDECLARED',
    ADD COLUMN social_insurance_at_application VARCHAR(32) NOT NULL DEFAULT 'UNDECLARED';

ALTER TABLE candidate_profile
    ADD CONSTRAINT ck_candidate_employer_settlement_at_application
    CHECK (employer_settlement_at_application IN ('DECLARED_MET', 'DECLARED_NOT_MET', 'UNDECLARED')),
    ADD CONSTRAINT ck_candidate_social_insurance_at_application
    CHECK (social_insurance_at_application IN ('DECLARED_MET', 'DECLARED_NOT_MET', 'UNDECLARED'));

-- 两个新的确认键。约束是全量重建，因此要把既有取值一并列出。
ALTER TABLE candidate_fact_confirmation
    DROP CONSTRAINT candidate_fact_confirmation_fact_key_check;

ALTER TABLE candidate_fact_confirmation
    ADD CONSTRAINT candidate_fact_confirmation_fact_key_check CHECK (fact_key IN (
        'BIRTH_DATE', 'HIGHEST_EDUCATION', 'MAJORS', 'GRADUATION_YEAR',
        'EXPERIENCE_YEARS', 'PROFESSIONAL_TITLES', 'PREFERRED_LOCATIONS',
        'ACCEPTED_EMPLOYMENT_TYPES', 'SKILLS', 'RESEARCH_KEYWORDS',
        'TARGET_JOB_FAMILIES', 'PREFERRED_ORGANIZATION_TYPES', 'EDUCATION_RECORDS',
        'GENDER', 'POLITICAL_AFFILIATION', 'EMPLOYMENT_HISTORY',
        'EMPLOYER_SETTLEMENT_AT_APPLICATION', 'SOCIAL_INSURANCE_AT_APPLICATION'
    ));
