CREATE TABLE source_completion_audit_snapshot (
    audit_snapshot_id UUID PRIMARY KEY,
    parent_snapshot_id UUID REFERENCES source_completion_audit_snapshot(audit_snapshot_id),
    registry_snapshot_id UUID NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    assessor_version VARCHAR(80) NOT NULL,
    registry_hash VARCHAR(128) NOT NULL,
    from_year INTEGER NOT NULL CHECK (from_year BETWEEN 2000 AND 2100),
    to_year INTEGER NOT NULL CHECK (to_year BETWEEN from_year AND 2100),
    coverage_through DATE,
    cutoff_at TIMESTAMPTZ NOT NULL,
    assessed_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX source_completion_audit_snapshot_range_idx
    ON source_completion_audit_snapshot (from_year, to_year, assessed_at DESC);
CREATE OR REPLACE FUNCTION reject_source_completion_audit_snapshot_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'source completion audit snapshots are append-only';
END $$;
CREATE TRIGGER source_completion_audit_snapshot_immutable
    BEFORE UPDATE OR DELETE ON source_completion_audit_snapshot
    FOR EACH ROW EXECUTE FUNCTION reject_source_completion_audit_snapshot_mutation();
