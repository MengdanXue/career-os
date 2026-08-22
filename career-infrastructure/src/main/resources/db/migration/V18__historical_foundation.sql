CREATE TABLE candidate_education_record (
    candidate_profile_id UUID NOT NULL REFERENCES candidate_profile(id) ON DELETE CASCADE,
    record_order INTEGER NOT NULL CHECK (record_order >= 0),
    institution_name VARCHAR(300),
    country_or_region VARCHAR(120),
    education_level VARCHAR(32) NOT NULL CHECK (education_level IN (
        'HIGH_SCHOOL', 'ASSOCIATE', 'BACHELOR', 'MASTER', 'DOCTORATE'
    )),
    major_name VARCHAR(300) NOT NULL CHECK (btrim(major_name) <> ''),
    graduation_year INTEGER CHECK (graduation_year BETWEEN 1900 AND 2100),
    graduation_month INTEGER CHECK (graduation_month BETWEEN 1 AND 12),
    completion_status VARCHAR(32) NOT NULL CHECK (completion_status IN ('COMPLETED', 'EXPECTED')),
    credential_verification_status VARCHAR(32) NOT NULL CHECK (credential_verification_status IN (
        'NOT_REQUIRED', 'PLANNED', 'IN_PROGRESS', 'VERIFIED', 'UNKNOWN'
    )),
    PRIMARY KEY (candidate_profile_id, record_order)
);

CREATE INDEX idx_candidate_education_profile_level
    ON candidate_education_record(candidate_profile_id, education_level, completion_status);

ALTER TABLE candidate_fact_confirmation
    DROP CONSTRAINT candidate_fact_confirmation_fact_key_check;

ALTER TABLE candidate_fact_confirmation
    ADD CONSTRAINT candidate_fact_confirmation_fact_key_check CHECK (fact_key IN (
        'BIRTH_DATE', 'HIGHEST_EDUCATION', 'MAJORS', 'GRADUATION_YEAR',
        'EXPERIENCE_YEARS', 'PROFESSIONAL_TITLES', 'PREFERRED_LOCATIONS',
        'ACCEPTED_EMPLOYMENT_TYPES', 'SKILLS', 'RESEARCH_KEYWORDS',
        'TARGET_JOB_FAMILIES', 'PREFERRED_ORGANIZATION_TYPES', 'EDUCATION_RECORDS'
    ));

INSERT INTO candidate_education_record (
    candidate_profile_id, record_order, institution_name, country_or_region,
    education_level, major_name, graduation_year, graduation_month,
    completion_status, credential_verification_status
) VALUES
(
    '01992f09-0000-7000-8000-000000000001', 0, NULL, NULL,
    'BACHELOR', '计算机科学与技术', 2014, NULL, 'COMPLETED', 'UNKNOWN'
),
(
    '01992f09-0000-7000-8000-000000000001', 1, '示例海外大学', '示例国',
    'MASTER', '计算机科学', 2027, NULL, 'EXPECTED', 'PLANNED'
)
ON CONFLICT (candidate_profile_id, record_order) DO NOTHING;

UPDATE candidate_profile
SET highest_education = 'BACHELOR',
    majors = '["计算机科学与技术"]'::jsonb,
    graduation_year = 2014,
    profile_version = 'profile-v18-real-education',
    updated_at = now()
WHERE id = '01992f09-0000-7000-8000-000000000001';

CREATE TABLE source_year_coverage (
    source_id UUID NOT NULL REFERENCES recruitment_source(id) ON DELETE CASCADE,
    recruitment_year INTEGER NOT NULL CHECK (recruitment_year BETWEEN 2000 AND 2100),
    status VARCHAR(40) NOT NULL CHECK (status IN (
        'NOT_DISCOVERED', 'DISCOVERED_NOT_FETCHED', 'ACCESS_FAILED',
        'FETCHED_NOT_PARSED', 'PARTIAL', 'COMPLETE', 'NO_TARGET_RECORDS'
    )),
    discovered_count INTEGER NOT NULL DEFAULT 0 CHECK (discovered_count >= 0),
    fetched_count INTEGER NOT NULL DEFAULT 0 CHECK (fetched_count >= 0),
    parsed_count INTEGER NOT NULL DEFAULT 0 CHECK (parsed_count >= 0),
    target_job_count INTEGER NOT NULL DEFAULT 0 CHECK (target_job_count >= 0),
    completion_basis TEXT,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (source_id, recruitment_year),
    CHECK (
        (status IN ('COMPLETE', 'NO_TARGET_RECORDS')
            AND completed_at IS NOT NULL
            AND completion_basis IS NOT NULL
            AND btrim(completion_basis) <> '')
        OR
        (status NOT IN ('COMPLETE', 'NO_TARGET_RECORDS') AND completed_at IS NULL)
    ),
    CHECK (status <> 'NO_TARGET_RECORDS' OR target_job_count = 0)
);

CREATE INDEX idx_source_year_coverage_year_status
    ON source_year_coverage(recruitment_year, status, source_id);

INSERT INTO source_year_coverage (
    source_id, recruitment_year, status, discovered_count, fetched_count,
    parsed_count, target_job_count, updated_at
)
SELECT source.id, year.value, 'NOT_DISCOVERED', 0, 0, 0, 0, now()
FROM recruitment_source source
CROSS JOIN generate_series(2024, 2026) AS year(value)
WHERE source.code IN ('ZJ_HRSS_INSTITUTION', 'HZ_HRSS_INSTITUTION')
ON CONFLICT (source_id, recruitment_year) DO NOTHING;
