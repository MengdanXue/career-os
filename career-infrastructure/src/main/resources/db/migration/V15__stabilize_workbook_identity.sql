alter table recruitment_event
    add column if not exists workbook_identity varchar(2000);

create unique index if not exists uk_recruitment_event_workbook_identity
    on recruitment_event(workbook_identity)
    where workbook_identity is not null;

-- V13 could not prove that every parent-URL event was created by the old workbook importer.
-- Evidence-backed announcement/HTML events must never be treated as disposable workbook snapshots.
update recruitment_event
set legacy_workbook_snapshot = false
where legacy_workbook_snapshot = true
  and evidence_ids <> '[]'::jsonb;
