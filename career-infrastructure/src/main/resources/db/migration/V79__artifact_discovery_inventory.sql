-- A discovery row is written before fetching an announcement or attachment.
-- It is intentionally mutable: the run and status describe the latest attempt,
-- while source_crawl_run and artifact_import_failure retain the historical run
-- and failure evidence.
create table if not exists artifact_discovery (
    id uuid primary key,
    source_id uuid not null references recruitment_source(id) on delete restrict,
    run_id uuid not null references source_crawl_run(id) on delete restrict,
    parent_document_id uuid references acquired_document(id) on delete set null,
    canonical_uri text not null,
    fetch_uri text not null,
    title text not null,
    document_kind varchar(32) not null,
    published_on date,
    status varchar(32) not null,
    error_code varchar(128),
    first_seen_at timestamptz not null,
    last_attempt_at timestamptz not null,
    attempt_count integer not null check (attempt_count > 0),
    constraint artifact_discovery_source_uri_uq unique (source_id, canonical_uri),
    constraint artifact_discovery_kind_ck check (document_kind in ('ANNOUNCEMENT', 'ATTACHMENT')),
    constraint artifact_discovery_status_ck check (
        status in ('DISCOVERED', 'FETCHED', 'FETCH_FAILED', 'PARSE_FAILED', 'PROCESSED')
    ),
    constraint artifact_discovery_attempt_time_ck check (last_attempt_at >= first_seen_at)
);

create index if not exists artifact_discovery_source_status_idx
    on artifact_discovery(source_id, status, last_attempt_at desc);
create index if not exists artifact_discovery_run_idx
    on artifact_discovery(run_id, last_attempt_at asc);
