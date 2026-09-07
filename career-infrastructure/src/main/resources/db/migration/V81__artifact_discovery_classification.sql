-- Classification is an explicit projection of the official title/URL. It
-- separates non-job documents from parser failures without inventing jobs.
alter table artifact_discovery
    add column if not exists classification varchar(40) not null default 'UNKNOWN';

alter table artifact_discovery
    add constraint artifact_discovery_classification_ck
        check (classification in (
            'ANNOUNCEMENT', 'JOB_TABLE', 'MAJOR_CATALOG', 'REGISTRATION_FORM',
            'RESULTS_OR_LIFECYCLE', 'UNKNOWN'));

create index if not exists artifact_discovery_classification_idx
    on artifact_discovery(source_id, classification, status);
