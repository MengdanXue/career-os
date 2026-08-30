ALTER TABLE acquired_document
    ADD COLUMN listing_metadata_fingerprint VARCHAR(64);

ALTER TABLE acquired_document
    ADD CONSTRAINT chk_acquired_document_listing_metadata_fingerprint
    CHECK (
        listing_metadata_fingerprint IS NULL
        OR listing_metadata_fingerprint ~ '^[0-9a-f]{64}$'
    );
