CREATE TABLE recruitment_event (
    id UUID PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    recruitment_year INTEGER NOT NULL CHECK (recruitment_year BETWEEN 2000 AND 2100),
    event_type VARCHAR(64) NOT NULL,
    published_on DATE,
    application_starts_on DATE,
    application_ends_on DATE,
    source_url TEXT NOT NULL,
    default_employment_type VARCHAR(64) NOT NULL DEFAULT 'UNKNOWN',
    evidence_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (application_starts_on IS NULL OR application_ends_on IS NULL OR application_ends_on >= application_starts_on)
);

CREATE TABLE organization (
    id UUID PRIMARY KEY,
    name VARCHAR(300) NOT NULL,
    organization_type VARCHAR(64) NOT NULL,
    administrative_level VARCHAR(100),
    province VARCHAR(100),
    city VARCHAR(100),
    district VARCHAR(100),
    parent_organization_id UUID REFERENCES organization(id),
    official_website TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE job_posting (
    id UUID PRIMARY KEY,
    recruitment_event_id UUID NOT NULL REFERENCES recruitment_event(id),
    organization_id UUID NOT NULL REFERENCES organization(id),
    external_job_code VARCHAR(120),
    title VARCHAR(300) NOT NULL,
    job_family VARCHAR(64) NOT NULL,
    employment_type VARCHAR(64) NOT NULL DEFAULT 'UNKNOWN',
    location VARCHAR(300),
    headcount INTEGER NOT NULL DEFAULT 1 CHECK (headcount > 0),
    minimum_education VARCHAR(32) NOT NULL,
    exact_majors JSONB NOT NULL DEFAULT '[]'::jsonb,
    accepted_graduation_years JSONB NOT NULL DEFAULT '[]'::jsonb,
    maximum_age INTEGER CHECK (maximum_age IS NULL OR maximum_age >= 16),
    age_reference_date DATE,
    minimum_experience_years INTEGER CHECK (minimum_experience_years IS NULL OR minimum_experience_years >= 0),
    required_professional_titles JSONB NOT NULL DEFAULT '[]'::jsonb,
    duties TEXT,
    source_url TEXT NOT NULL,
    evidence_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    stable_job_key VARCHAR(180) UNIQUE,
    content_fingerprint VARCHAR(64),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_job_event ON job_posting(recruitment_event_id);
CREATE INDEX idx_job_organization ON job_posting(organization_id);
CREATE INDEX idx_job_family_active ON job_posting(job_family, active);

CREATE TABLE candidate_profile (
    id UUID PRIMARY KEY,
    display_name VARCHAR(200) NOT NULL,
    birth_year INTEGER NOT NULL,
    birth_month INTEGER NOT NULL CHECK (birth_month BETWEEN 1 AND 12),
    birth_day INTEGER CHECK (birth_day BETWEEN 1 AND 31),
    highest_education VARCHAR(32) NOT NULL,
    education_type VARCHAR(100),
    majors JSONB NOT NULL DEFAULT '[]'::jsonb,
    graduation_year INTEGER,
    experience_years INTEGER CHECK (experience_years IS NULL OR experience_years >= 0),
    professional_titles JSONB NOT NULL DEFAULT '[]'::jsonb,
    preferred_locations JSONB NOT NULL DEFAULT '[]'::jsonb,
    accepted_employment_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    profile_version VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE policy_rule (
    id UUID PRIMARY KEY,
    rule_type VARCHAR(64) NOT NULL,
    name VARCHAR(300) NOT NULL,
    jurisdiction VARCHAR(200),
    valid_from DATE,
    valid_until DATE,
    parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
    evidence_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (valid_from IS NULL OR valid_until IS NULL OR valid_until >= valid_from)
);

CREATE TABLE evidence (
    id UUID PRIMARY KEY,
    evidence_type VARCHAR(64) NOT NULL,
    source_url TEXT NOT NULL,
    source_title VARCHAR(500),
    excerpt TEXT,
    content_hash VARCHAR(64),
    captured_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE policy_rule ADD CONSTRAINT fk_policy_evidence FOREIGN KEY (evidence_id) REFERENCES evidence(id);

CREATE TABLE eligibility_assessment (
    id UUID PRIMARY KEY,
    candidate_profile_id UUID NOT NULL REFERENCES candidate_profile(id),
    job_posting_id UUID NOT NULL REFERENCES job_posting(id),
    status VARCHAR(32) NOT NULL,
    rule_results JSONB NOT NULL,
    evidence_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    evaluator_version VARCHAR(80) NOT NULL,
    assessed_at TIMESTAMPTZ NOT NULL,
    UNIQUE (candidate_profile_id, job_posting_id, evaluator_version)
);

CREATE TABLE opportunity (
    id UUID PRIMARY KEY,
    candidate_profile_id UUID NOT NULL REFERENCES candidate_profile(id),
    job_posting_id UUID NOT NULL REFERENCES job_posting(id),
    eligibility_assessment_id UUID REFERENCES eligibility_assessment(id),
    status VARCHAR(32) NOT NULL,
    match_score INTEGER NOT NULL CHECK (match_score BETWEEN 0 AND 100),
    decision_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (candidate_profile_id, job_posting_id)
);
