CREATE TABLE source_artifact (
    id UUID PRIMARY KEY,
    sha256 TEXT NOT NULL UNIQUE CHECK (length(sha256) = 64),
    media_type TEXT NOT NULL CHECK (media_type IN ('text/html','application/pdf')),
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    storage_uri TEXT NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE evidence ADD COLUMN source_artifact_id UUID REFERENCES source_artifact(id) ON DELETE RESTRICT;
CREATE INDEX idx_evidence_source_artifact ON evidence(source_artifact_id);

CREATE TABLE evidence_fragment (
    id UUID PRIMARY KEY,
    evidence_id UUID NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
    locator_type TEXT NOT NULL CHECK (locator_type IN ('HTML','PDF')),
    locator JSONB NOT NULL CHECK (jsonb_typeof(locator) = 'object'),
    verbatim_text TEXT NOT NULL CHECK (length(trim(verbatim_text)) > 0),
    content_hash TEXT NOT NULL CHECK (length(content_hash) = 64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_evidence_fragment_evidence ON evidence_fragment(evidence_id);

CREATE TABLE extraction_run (
    id UUID PRIMARY KEY,
    evidence_id UUID NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
    organization_id UUID REFERENCES organization(id) ON DELETE RESTRICT,
    recruitment_event_id UUID REFERENCES recruitment_event(id) ON DELETE RESTRICT,
    input_fingerprint TEXT NOT NULL UNIQUE CHECK (length(input_fingerprint) = 64),
    source_type TEXT NOT NULL CHECK (source_type IN ('HTML','PDF')),
    parser_name TEXT NOT NULL,
    parser_version TEXT NOT NULL,
    extractor_name TEXT NOT NULL,
    extractor_version TEXT NOT NULL,
    model_name TEXT,
    prompt_version TEXT,
    schema_version TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('RAW','PARSED','NORMALIZED','REVIEW_REQUIRED','VERIFIED','REJECTED','FAILED')),
    confidence DOUBLE PRECISION NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    proposed_payload JSONB NOT NULL CHECK (jsonb_typeof(proposed_payload) = 'object'),
    model_response TEXT,
    error_code TEXT,
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_extraction_evidence ON extraction_run(evidence_id);
CREATE INDEX idx_extraction_organization ON extraction_run(organization_id);
CREATE INDEX idx_extraction_event ON extraction_run(recruitment_event_id);
CREATE INDEX idx_extraction_status_started ON extraction_run(status, started_at DESC);

CREATE TABLE review_item (
    id UUID PRIMARY KEY,
    extraction_run_id UUID NOT NULL UNIQUE REFERENCES extraction_run(id) ON DELETE RESTRICT,
    status TEXT NOT NULL CHECK (status IN ('PENDING','RESOLVED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);
CREATE INDEX idx_review_pending ON review_item(created_at, id) WHERE status = 'PENDING';

CREATE TABLE review_issue (
    id UUID PRIMARY KEY,
    review_item_id UUID NOT NULL REFERENCES review_item(id) ON DELETE RESTRICT,
    reason_code TEXT NOT NULL,
    field_path TEXT,
    message TEXT NOT NULL,
    evidence_fragment_id UUID REFERENCES evidence_fragment(id) ON DELETE RESTRICT
);
CREATE INDEX idx_review_issue_item ON review_issue(review_item_id);
CREATE INDEX idx_review_issue_fragment ON review_issue(evidence_fragment_id);

CREATE TABLE review_action (
    id UUID PRIMARY KEY,
    review_item_id UUID NOT NULL REFERENCES review_item(id) ON DELETE RESTRICT,
    decision TEXT NOT NULL CHECK (decision IN ('CONFIRM','CORRECT','REJECT','NEED_MORE_EVIDENCE')),
    expected_version BIGINT NOT NULL CHECK (expected_version >= 0),
    original_payload JSONB NOT NULL CHECK (jsonb_typeof(original_payload) = 'object'),
    corrected_payload JSONB CHECK (corrected_payload IS NULL OR jsonb_typeof(corrected_payload) = 'object'),
    note TEXT,
    acted_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_review_action_item_time ON review_action(review_item_id, acted_at);
