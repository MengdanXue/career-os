CREATE TABLE candidate_fact_confirmation (
    candidate_profile_id UUID NOT NULL REFERENCES candidate_profile(id) ON DELETE CASCADE,
    fact_key TEXT NOT NULL CHECK (fact_key IN (
        'BIRTH_DATE', 'HIGHEST_EDUCATION', 'MAJORS', 'GRADUATION_YEAR',
        'EXPERIENCE_YEARS', 'PROFESSIONAL_TITLES', 'PREFERRED_LOCATIONS',
        'ACCEPTED_EMPLOYMENT_TYPES', 'SKILLS', 'RESEARCH_KEYWORDS',
        'TARGET_JOB_FAMILIES', 'PREFERRED_ORGANIZATION_TYPES'
    )),
    status TEXT NOT NULL CHECK (status IN ('UNCONFIRMED', 'CONFIRMED', 'UNKNOWN')),
    value_fingerprint TEXT NOT NULL CHECK (value_fingerprint ~ '^[0-9a-f]{64}$'),
    source TEXT NOT NULL CHECK (source IN ('SEEDED', 'IMPORTED', 'USER_EDITED', 'USER_CONFIRMED')),
    confirmed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (candidate_profile_id, fact_key),
    CHECK ((status = 'CONFIRMED' AND confirmed_at IS NOT NULL) OR (status <> 'CONFIRMED' AND confirmed_at IS NULL))
);
