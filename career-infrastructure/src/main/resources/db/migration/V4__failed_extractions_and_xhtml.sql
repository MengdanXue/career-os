ALTER TABLE extraction_run
    ALTER COLUMN proposed_payload DROP NOT NULL;

ALTER TABLE extraction_run
    DROP CONSTRAINT extraction_run_proposed_payload_check;

ALTER TABLE extraction_run
    ADD CONSTRAINT extraction_run_proposed_payload_check
    CHECK (proposed_payload IS NULL OR jsonb_typeof(proposed_payload) = 'object');

ALTER TABLE source_artifact
    DROP CONSTRAINT source_artifact_media_type_check;

ALTER TABLE source_artifact
    ADD CONSTRAINT source_artifact_media_type_check
    CHECK (media_type IN ('text/html', 'application/xhtml+xml', 'application/pdf'));
