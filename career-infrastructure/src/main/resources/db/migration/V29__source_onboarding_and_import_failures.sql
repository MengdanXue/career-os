ALTER TABLE source_year_coverage
    ADD COLUMN listing_page_count INTEGER NOT NULL DEFAULT 0 CHECK (listing_page_count >= 0),
    ADD COLUMN filtered_count INTEGER NOT NULL DEFAULT 0 CHECK (filtered_count >= 0),
    ADD COLUMN failed_count INTEGER NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
    ADD COLUMN earliest_published_on DATE,
    ADD COLUMN latest_published_on DATE,
    ADD COLUMN stop_reason TEXT,
    ADD CONSTRAINT ck_source_year_coverage_published_range CHECK (
        earliest_published_on IS NULL OR latest_published_on IS NULL
        OR earliest_published_on <= latest_published_on
    );

CREATE TABLE source_onboarding_checkpoint (
    source_id UUID NOT NULL REFERENCES recruitment_source(id) ON DELETE CASCADE,
    checkpoint VARCHAR(40) NOT NULL CHECK (checkpoint IN (
        'REGISTERED', 'CONTRACT_VERIFIED', 'LIVE_SMOKE_VERIFIED',
        'BACKFILL_COMPLETE', 'INCREMENTAL_VERIFIED'
    )),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'VERIFIED', 'FAILED')),
    evidence TEXT,
    verified_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (source_id, checkpoint),
    CHECK (status <> 'VERIFIED' OR (evidence IS NOT NULL AND btrim(evidence) <> ''))
);

CREATE TABLE artifact_import_failure (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES source_crawl_run(id) ON DELETE CASCADE,
    source_id UUID NOT NULL REFERENCES recruitment_source(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES acquired_document(id) ON DELETE CASCADE,
    stage VARCHAR(50) NOT NULL CHECK (stage IN (
        'DISCOVERY_CONTRACT_CHANGED', 'REMOTE_ACCESS_FAILED', 'ARTIFACT_DOWNLOAD_FAILED',
        'UNSUPPORTED_DOCUMENT', 'DOCUMENT_PARSE_FAILED', 'ROW_PARSE_FAILED',
        'NORMALIZATION_FAILED', 'EVIDENCE_LINK_FAILED'
    )),
    sheet_name TEXT,
    row_number INTEGER CHECK (row_number IS NULL OR row_number > 0),
    error_code TEXT NOT NULL CHECK (btrim(error_code) <> ''),
    safe_message TEXT NOT NULL CHECK (btrim(safe_message) <> ''),
    occurred_at TIMESTAMPTZ NOT NULL,
    CHECK (stage <> 'ROW_PARSE_FAILED' OR (
        sheet_name IS NOT NULL AND btrim(sheet_name) <> '' AND row_number > 0
    ))
);

CREATE INDEX idx_artifact_import_failure_source_run
    ON artifact_import_failure(source_id, run_id, occurred_at, id);
