CREATE TABLE recruitment_lifecycle_document (
    id UUID PRIMARY KEY,
    source_url TEXT NOT NULL,
    title VARCHAR(500) NOT NULL,
    recruitment_year INTEGER NOT NULL CHECK (recruitment_year BETWEEN 2000 AND 2100),
    published_on DATE,
    stage VARCHAR(40) NOT NULL CHECK (stage IN (
        'QUALIFICATION_REVIEW', 'SCORE_RESULT', 'INTERVIEW', 'PHYSICAL_EXAM',
        'INVESTIGATION', 'PUBLICATION', 'APPOINTMENT'
    )),
    evidence_id UUID NOT NULL REFERENCES evidence(id),
    matched_event_id UUID REFERENCES recruitment_event(id),
    match_status VARCHAR(20) NOT NULL CHECK (match_status IN ('MATCHED', 'UNMATCHED', 'AMBIGUOUS')),
    match_basis VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_recruitment_lifecycle_source_stage UNIQUE (source_url, stage),
    CONSTRAINT ck_recruitment_lifecycle_match CHECK (
        (match_status = 'MATCHED' AND matched_event_id IS NOT NULL)
        OR (match_status <> 'MATCHED' AND matched_event_id IS NULL)
    )
);

CREATE INDEX idx_recruitment_lifecycle_event
    ON recruitment_lifecycle_document(matched_event_id);
CREATE INDEX idx_recruitment_lifecycle_status
    ON recruitment_lifecycle_document(match_status, recruitment_year);
