ALTER TABLE source_artifact
    DROP CONSTRAINT source_artifact_media_type_check;

ALTER TABLE source_artifact
    ADD CONSTRAINT source_artifact_media_type_check
    CHECK (media_type IN ('text/html', 'application/xhtml+xml', 'application/pdf',
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document'));

ALTER TABLE evidence_fragment
    DROP CONSTRAINT evidence_fragment_locator_type_check;

ALTER TABLE evidence_fragment
    ADD CONSTRAINT evidence_fragment_locator_type_check
    CHECK (locator_type IN ('HTML', 'PDF', 'SPREADSHEET', 'DOCX'));

ALTER TABLE extraction_run
    DROP CONSTRAINT extraction_run_source_type_check;

ALTER TABLE extraction_run
    ADD CONSTRAINT extraction_run_source_type_check
    CHECK (source_type IN ('HTML', 'PDF', 'DOCX'));
