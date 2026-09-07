-- Keep the bytes fetched for a discovered item auditable even when the
-- normalized document fingerprint intentionally ignores dynamic HTML.
alter table artifact_discovery
    add column if not exists media_type text,
    add column if not exists raw_checksum varchar(64),
    add column if not exists size_bytes bigint not null default 0;

alter table artifact_discovery
    add constraint artifact_discovery_raw_checksum_ck
        check (raw_checksum is null or raw_checksum ~ '^[0-9a-f]{64}$'),
    add constraint artifact_discovery_size_ck
        check (size_bytes >= 0);

create index if not exists artifact_discovery_raw_checksum_idx
    on artifact_discovery(raw_checksum)
    where raw_checksum is not null;
