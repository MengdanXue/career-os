CREATE TABLE recruitment_source (
    id UUID PRIMARY KEY,
    code VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    base_uri TEXT NOT NULL,
    entry_uri TEXT NOT NULL,
    source_type VARCHAR(40) NOT NULL CHECK (source_type IN (
        'OFFICIAL_GOVERNMENT','OFFICIAL_ORGANIZATION','OFFICIAL_SOE',
        'OFFICIAL_UNIVERSITY','AGGREGATOR','UNKNOWN')),
    region VARCHAR(100) NOT NULL,
    crawl_mode VARCHAR(30) NOT NULL CHECK (crawl_mode IN ('STATIC_HTML','PLAYWRIGHT')),
    enabled BOOLEAN NOT NULL,
    cron_expression VARCHAR(80) NOT NULL,
    time_zone VARCHAR(80) NOT NULL,
    minimum_request_interval_ms BIGINT NOT NULL CHECK (minimum_request_interval_ms >= 0),
    configuration JSONB NOT NULL CHECK (jsonb_typeof(configuration) = 'object'),
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    next_due_at TIMESTAMPTZ,
    consecutive_failure_count INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failure_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_recruitment_source_due
    ON recruitment_source(next_due_at, id)
    WHERE enabled;

CREATE TABLE source_crawl_run (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES recruitment_source(id) ON DELETE RESTRICT,
    trigger_type VARCHAR(20) NOT NULL CHECK (trigger_type IN ('SCHEDULED','MANUAL')),
    status VARCHAR(30) NOT NULL CHECK (status IN (
        'RUNNING','SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','SKIPPED_LOCKED')),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    discovered_count INTEGER NOT NULL DEFAULT 0 CHECK (discovered_count >= 0),
    fetched_count INTEGER NOT NULL DEFAULT 0 CHECK (fetched_count >= 0),
    unchanged_count INTEGER NOT NULL DEFAULT 0 CHECK (unchanged_count >= 0),
    added_count INTEGER NOT NULL DEFAULT 0 CHECK (added_count >= 0),
    updated_count INTEGER NOT NULL DEFAULT 0 CHECK (updated_count >= 0),
    deactivated_count INTEGER NOT NULL DEFAULT 0 CHECK (deactivated_count >= 0),
    failed_count INTEGER NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
    error_code TEXT,
    error_message TEXT,
    CHECK ((status = 'RUNNING' AND completed_at IS NULL) OR
           (status <> 'RUNNING' AND completed_at IS NOT NULL))
);

CREATE INDEX idx_source_crawl_run_source_started
    ON source_crawl_run(source_id, started_at DESC, id DESC);
CREATE INDEX idx_source_crawl_run_status_started
    ON source_crawl_run(status, started_at DESC, id DESC);

CREATE TABLE acquired_document (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES recruitment_source(id) ON DELETE RESTRICT,
    canonical_uri TEXT NOT NULL,
    parent_document_id UUID,
    document_kind VARCHAR(20) NOT NULL CHECK (document_kind IN ('ANNOUNCEMENT','ATTACHMENT')),
    media_type VARCHAR(150) NOT NULL,
    content_fingerprint VARCHAR(64) NOT NULL CHECK (content_fingerprint ~ '^[0-9a-f]{64}$'),
    etag TEXT,
    last_modified TEXT,
    storage_uri TEXT NOT NULL,
    document_state VARCHAR(20) NOT NULL CHECK (document_state IN ('ACTIVE','DEACTIVATED')),
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    last_changed_at TIMESTAMPTZ NOT NULL,
    last_gone_at TIMESTAMPTZ,
    consecutive_gone_count INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_gone_count >= 0),
    last_http_status INTEGER NOT NULL CHECK (last_http_status >= 0 AND last_http_status <= 599),
    last_processed_fingerprint VARCHAR(64) CHECK (
        last_processed_fingerprint IS NULL OR last_processed_fingerprint ~ '^[0-9a-f]{64}$'),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (source_id, canonical_uri),
    UNIQUE (id, source_id),
    CONSTRAINT fk_acquired_document_parent_same_source
        FOREIGN KEY (parent_document_id, source_id)
        REFERENCES acquired_document(id, source_id)
        ON DELETE RESTRICT,
    CHECK ((document_kind = 'ANNOUNCEMENT' AND parent_document_id IS NULL) OR
           (document_kind = 'ATTACHMENT' AND parent_document_id IS NOT NULL))
);

CREATE INDEX idx_acquired_document_source_state
    ON acquired_document(source_id, document_state, last_seen_at DESC);

CREATE TABLE acquisition_change (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES source_crawl_run(id) ON DELETE RESTRICT,
    source_id UUID NOT NULL REFERENCES recruitment_source(id) ON DELETE RESTRICT,
    document_id UUID NOT NULL,
    change_type VARCHAR(20) NOT NULL CHECK (change_type IN ('ADDED','UPDATED','DEACTIVATED')),
    previous_fingerprint VARCHAR(64) CHECK (
        previous_fingerprint IS NULL OR previous_fingerprint ~ '^[0-9a-f]{64}$'),
    current_fingerprint VARCHAR(64) NOT NULL CHECK (current_fingerprint ~ '^[0-9a-f]{64}$'),
    canonical_uri TEXT NOT NULL,
    job_delta_summary JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(job_delta_summary) = 'object'),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_acquisition_change_document_same_source
        FOREIGN KEY (document_id, source_id)
        REFERENCES acquired_document(id, source_id)
        ON DELETE RESTRICT,
    UNIQUE (document_id, change_type, current_fingerprint)
);

CREATE INDEX idx_acquisition_change_cursor
    ON acquisition_change(occurred_at, id);
CREATE INDEX idx_acquisition_change_source_cursor
    ON acquisition_change(source_id, occurred_at, id);

INSERT INTO recruitment_source (
    id, code, name, base_uri, entry_uri, source_type, region, crawl_mode,
    enabled, cron_expression, time_zone, minimum_request_interval_ms,
    configuration, next_due_at
) VALUES
(
    '01992f09-0000-7000-8000-000000000301',
    'ZJ_HRSS_INSTITUTION',
    '浙江省人力资源和社会保障厅事业单位招聘',
    'https://rlsbt.zj.gov.cn/',
    'https://rlsbt.zj.gov.cn/col/col1229743683/index.html',
    'OFFICIAL_GOVERNMENT',
    '浙江',
    'STATIC_HTML',
    TRUE,
    '0 10 8 * * *',
    'Asia/Shanghai',
    1000,
    '{"articleUrlRegex":"^https://rlsbt\\.zj\\.gov\\.cn/art/[0-9]{4}/[0-9]+/[0-9]+/art_[A-Za-z0-9_]+\\.html$","linkSelector":"a[href]","attachmentSelector":"a[href]","titleIncludeRegex":"招聘|招考|选聘|引进","titleExcludeRegex":"拟聘|公示|成绩|体检|递补","maxListPages":2}'::jsonb,
    now()
),
(
    '01992f09-0000-7000-8000-000000000302',
    'HZ_HRSS_INSTITUTION',
    '杭州市人力资源和社会保障局事业单位招聘',
    'https://hrss.hangzhou.gov.cn/',
    'https://hrss.hangzhou.gov.cn/col/col1229782005/index.html',
    'OFFICIAL_GOVERNMENT',
    '杭州',
    'STATIC_HTML',
    TRUE,
    '0 20 8 * * *',
    'Asia/Shanghai',
    1000,
    '{"articleUrlRegex":"^https://hrss\\.hangzhou\\.gov\\.cn/art/[0-9]{4}/[0-9]+/[0-9]+/art_[A-Za-z0-9_]+\\.html$","linkSelector":"a[href]","attachmentSelector":"a[href]","titleIncludeRegex":"招聘|招考|选聘|引进","titleExcludeRegex":"拟聘|公示|成绩|体检|递补","maxListPages":2}'::jsonb,
    now()
);
