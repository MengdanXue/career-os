ALTER TABLE candidate_profile
    ADD COLUMN gender VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN political_affiliation VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN';

ALTER TABLE candidate_profile
    ADD CONSTRAINT ck_candidate_profile_gender
        CHECK (gender IN ('FEMALE', 'MALE', 'OTHER', 'UNKNOWN')),
    ADD CONSTRAINT ck_candidate_profile_political_affiliation
        CHECK (political_affiliation IN ('CPC_MEMBER', 'CPC_PROBATIONARY', 'NON_MEMBER', 'UNKNOWN'));

CREATE TABLE candidate_employment_record (
    candidate_profile_id UUID NOT NULL REFERENCES candidate_profile(id) ON DELETE CASCADE,
    record_order INTEGER NOT NULL CHECK (record_order >= 0),
    employer_name VARCHAR(300) NOT NULL CHECK (btrim(employer_name) <> ''),
    role_title VARCHAR(300) NOT NULL CHECK (btrim(role_title) <> ''),
    starts_on DATE NOT NULL,
    ends_on DATE,
    employment_mode VARCHAR(24) NOT NULL CHECK (employment_mode IN ('FULL_TIME', 'PART_TIME', 'INTERNSHIP', 'UNKNOWN')),
    verification_status VARCHAR(24) NOT NULL CHECK (verification_status IN ('UNVERIFIED', 'PARTIAL', 'VERIFIED', 'REJECTED')),
    evidence_types JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(evidence_types) = 'array'),
    PRIMARY KEY (candidate_profile_id, record_order),
    CHECK (ends_on IS NULL OR ends_on >= starts_on)
);

CREATE INDEX idx_candidate_employment_profile_dates
    ON candidate_employment_record(candidate_profile_id, starts_on, ends_on);

ALTER TABLE candidate_fact_confirmation
    DROP CONSTRAINT candidate_fact_confirmation_fact_key_check;

ALTER TABLE candidate_fact_confirmation
    ADD CONSTRAINT candidate_fact_confirmation_fact_key_check CHECK (fact_key IN (
        'BIRTH_DATE', 'HIGHEST_EDUCATION', 'MAJORS', 'GRADUATION_YEAR',
        'EXPERIENCE_YEARS', 'PROFESSIONAL_TITLES', 'PREFERRED_LOCATIONS',
        'ACCEPTED_EMPLOYMENT_TYPES', 'SKILLS', 'RESEARCH_KEYWORDS',
        'TARGET_JOB_FAMILIES', 'PREFERRED_ORGANIZATION_TYPES', 'EDUCATION_RECORDS',
        'GENDER', 'POLITICAL_AFFILIATION', 'EMPLOYMENT_HISTORY'
    ));

UPDATE candidate_profile
SET birth_day = 31,
    gender = 'FEMALE',
    updated_at = now()
WHERE id = '01992f09-0000-7000-8000-000000000001';

INSERT INTO candidate_fact_confirmation (
    candidate_profile_id, fact_key, status, value_fingerprint, source, confirmed_at, updated_at
) VALUES
(
    '01992f09-0000-7000-8000-000000000001', 'BIRTH_DATE', 'CONFIRMED',
    '4f545c6c9542c13471d0eddf2b58d136f506c260fef0bf1975b8f2c49e18c525',
    'USER_CONFIRMED', now(), now()
),
(
    '01992f09-0000-7000-8000-000000000001', 'GENDER', 'CONFIRMED',
    'cf112cb65cc0fbbbd85eeaa20d3ac834bd954e7539ad8008b6487dbe47edb61f',
    'USER_CONFIRMED', now(), now()
)
ON CONFLICT (candidate_profile_id, fact_key) DO UPDATE SET
    status = excluded.status,
    value_fingerprint = excluded.value_fingerprint,
    source = excluded.source,
    confirmed_at = excluded.confirmed_at,
    updated_at = excluded.updated_at;
