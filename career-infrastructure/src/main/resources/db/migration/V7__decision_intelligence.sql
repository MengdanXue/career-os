ALTER TABLE candidate_profile
    ADD COLUMN skills JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN research_keywords JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN target_job_families JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN preferred_organization_types JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE eligibility_assessment
    ADD COLUMN profile_version TEXT NOT NULL DEFAULT 'legacy',
    ADD COLUMN job_content_fingerprint TEXT NOT NULL DEFAULT 'legacy';

DO $$
DECLARE constraint_name TEXT;
BEGIN
    SELECT c.conname INTO constraint_name
    FROM pg_constraint c
    WHERE c.conrelid = 'eligibility_assessment'::regclass
      AND c.contype = 'u'
    LIMIT 1;
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE eligibility_assessment DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

ALTER TABLE eligibility_assessment
    ADD CONSTRAINT uk_eligibility_assessment_input UNIQUE
    (candidate_profile_id, job_posting_id, profile_version, job_content_fingerprint, evaluator_version);

CREATE INDEX idx_eligibility_candidate ON eligibility_assessment(candidate_profile_id);
CREATE INDEX idx_eligibility_job ON eligibility_assessment(job_posting_id);

CREATE TABLE fit_assessment (
    id UUID PRIMARY KEY,
    candidate_profile_id UUID NOT NULL REFERENCES candidate_profile(id) ON DELETE CASCADE,
    job_posting_id UUID NOT NULL REFERENCES job_posting(id) ON DELETE CASCADE,
    score INTEGER NOT NULL CHECK (score BETWEEN 0 AND 100),
    coverage_percent INTEGER NOT NULL CHECK (coverage_percent BETWEEN 0 AND 100),
    evaluator_version TEXT NOT NULL,
    profile_version TEXT NOT NULL,
    job_content_fingerprint TEXT NOT NULL,
    assessed_at TIMESTAMPTZ NOT NULL,
    UNIQUE (candidate_profile_id, job_posting_id, profile_version, job_content_fingerprint, evaluator_version)
);
CREATE INDEX idx_fit_candidate_job ON fit_assessment(candidate_profile_id, job_posting_id);
CREATE INDEX idx_fit_job ON fit_assessment(job_posting_id);

CREATE TABLE stability_assessment (
    id UUID PRIMARY KEY,
    candidate_profile_id UUID NOT NULL REFERENCES candidate_profile(id) ON DELETE CASCADE,
    job_posting_id UUID NOT NULL REFERENCES job_posting(id) ON DELETE CASCADE,
    score INTEGER NOT NULL CHECK (score BETWEEN 0 AND 100),
    coverage_percent INTEGER NOT NULL CHECK (coverage_percent BETWEEN 0 AND 100),
    evaluator_version TEXT NOT NULL,
    profile_version TEXT NOT NULL,
    job_content_fingerprint TEXT NOT NULL,
    assessed_at TIMESTAMPTZ NOT NULL,
    UNIQUE (candidate_profile_id, job_posting_id, profile_version, job_content_fingerprint, evaluator_version)
);
CREATE INDEX idx_stability_candidate_job ON stability_assessment(candidate_profile_id, job_posting_id);
CREATE INDEX idx_stability_job ON stability_assessment(job_posting_id);

CREATE TABLE decision_assessment (
    id UUID PRIMARY KEY,
    candidate_profile_id UUID NOT NULL REFERENCES candidate_profile(id) ON DELETE CASCADE,
    job_posting_id UUID NOT NULL REFERENCES job_posting(id) ON DELETE CASCADE,
    eligibility_assessment_id UUID NOT NULL REFERENCES eligibility_assessment(id) ON DELETE RESTRICT,
    fit_assessment_id UUID NOT NULL REFERENCES fit_assessment(id) ON DELETE RESTRICT,
    stability_assessment_id UUID NOT NULL REFERENCES stability_assessment(id) ON DELETE RESTRICT,
    eligibility_status TEXT NOT NULL CHECK (eligibility_status IN ('ELIGIBLE','LIKELY_ELIGIBLE','UNCERTAIN','LIKELY_INELIGIBLE','INELIGIBLE')),
    opportunity_tier TEXT NOT NULL CHECK (opportunity_tier IN ('T1','T2','T3','EXCLUDED')),
    recommendation_status TEXT NOT NULL CHECK (recommendation_status IN ('RECOMMENDED','REVIEW','NOT_RECOMMENDED','EXCLUDED')),
    fit_score INTEGER NOT NULL CHECK (fit_score BETWEEN 0 AND 100),
    stability_score INTEGER NOT NULL CHECK (stability_score BETWEEN 0 AND 100),
    coverage_percent INTEGER NOT NULL CHECK (coverage_percent BETWEEN 0 AND 100),
    evaluator_version TEXT NOT NULL,
    profile_version TEXT NOT NULL,
    job_content_fingerprint TEXT NOT NULL,
    assessed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_decision_assessment_input UNIQUE
        (candidate_profile_id, job_posting_id, profile_version, job_content_fingerprint, evaluator_version)
);
CREATE INDEX idx_decision_candidate_rank ON decision_assessment(candidate_profile_id, opportunity_tier, fit_score DESC, stability_score DESC);
CREATE INDEX idx_decision_job ON decision_assessment(job_posting_id);
CREATE INDEX idx_decision_eligibility ON decision_assessment(eligibility_assessment_id);
CREATE INDEX idx_decision_fit ON decision_assessment(fit_assessment_id);
CREATE INDEX idx_decision_stability ON decision_assessment(stability_assessment_id);

CREATE TABLE assessment_dimension (
    id UUID PRIMARY KEY,
    decision_assessment_id UUID NOT NULL REFERENCES decision_assessment(id) ON DELETE CASCADE,
    assessment_kind TEXT NOT NULL CHECK (assessment_kind IN ('FIT','STABILITY')),
    dimension_type TEXT NOT NULL,
    achieved_points INTEGER NOT NULL CHECK (achieved_points >= 0),
    maximum_points INTEGER NOT NULL CHECK (maximum_points > 0),
    fact_status TEXT NOT NULL CHECK (fact_status IN ('EXPLICIT','INTERPRETED','UNKNOWN')),
    reason_code TEXT NOT NULL,
    explanation TEXT NOT NULL,
    CHECK (achieved_points <= maximum_points),
    CHECK (fact_status <> 'UNKNOWN' OR achieved_points = 0),
    UNIQUE (decision_assessment_id, assessment_kind, dimension_type)
);
CREATE INDEX idx_assessment_dimension_decision ON assessment_dimension(decision_assessment_id);

CREATE TABLE assessment_dimension_evidence (
    assessment_dimension_id UUID NOT NULL REFERENCES assessment_dimension(id) ON DELETE CASCADE,
    evidence_id UUID NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
    PRIMARY KEY (assessment_dimension_id, evidence_id)
);
CREATE INDEX idx_dimension_evidence_evidence ON assessment_dimension_evidence(evidence_id);

CREATE TABLE organization_stability_fact (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    dimension_type TEXT NOT NULL CHECK (dimension_type IN ('FUNDING_STABILITY','ORGANIZATION_STABILITY','POLICY_STABILITY','BUSINESS_VOLATILITY','LAYOFF_RISK')),
    achieved_points INTEGER NOT NULL CHECK (achieved_points >= 0),
    maximum_points INTEGER NOT NULL CHECK (maximum_points > 0),
    reason_code TEXT NOT NULL,
    explanation TEXT NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CHECK (achieved_points <= maximum_points)
);
CREATE INDEX idx_organization_stability_fact_org ON organization_stability_fact(organization_id);

CREATE TABLE organization_stability_fact_evidence (
    organization_stability_fact_id UUID NOT NULL REFERENCES organization_stability_fact(id) ON DELETE CASCADE,
    evidence_id UUID NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
    PRIMARY KEY (organization_stability_fact_id, evidence_id)
);
CREATE INDEX idx_stability_fact_evidence ON organization_stability_fact_evidence(evidence_id);
