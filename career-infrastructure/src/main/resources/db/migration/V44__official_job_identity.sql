ALTER TABLE job_posting
    ADD COLUMN IF NOT EXISTS actual_employer VARCHAR(500),
    ADD COLUMN IF NOT EXISTS worksite VARCHAR(500),
    ADD COLUMN IF NOT EXISTS employment_identity_evidence TEXT;
