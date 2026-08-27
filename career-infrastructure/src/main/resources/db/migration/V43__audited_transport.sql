ALTER TABLE acquired_document
    ADD COLUMN transport_risk VARCHAR(32) NOT NULL DEFAULT 'NONE';

ALTER TABLE acquired_document
    ADD CONSTRAINT ck_acquired_document_transport_risk
    CHECK (transport_risk IN ('NONE', 'PLAINTEXT_OFFICIAL_HTTP'));
