-- 复核人对"是否属于目标范围"的决定台账。
--
-- 在此之前，没有任何路径会把 job_admission 写成 INCLUDED：自动分类对目标技术岗只给 NEEDS_REVIEW，
-- 而可信机会池要 VERIFIED + INCLUDED，于是可信池在结构上恒为零。人工确认是补上的那一环。
--
-- 只增不改：谁、何时、依据哪一版岗位内容（content_fingerprint）、做了什么决定、决定之后的状态。
-- 岗位内容再变化时，V9 的触发器照旧把 job_admission 重置为 RAW / NEEDS_REVIEW 且 human_verified=false；
-- 这里的历史记录保留，用来回答"它曾经为什么在池子里"。

CREATE TABLE job_admission_scope_decision (
    id                  UUID         PRIMARY KEY,
    job_posting_id      UUID         NOT NULL REFERENCES job_posting (id) ON DELETE CASCADE,
    decision            VARCHAR(16)  NOT NULL,
    content_fingerprint VARCHAR(128) NOT NULL,
    reviewer            VARCHAR(120) NOT NULL,
    note                VARCHAR(500),
    resulting_quality   VARCHAR(32)  NOT NULL,
    resulting_scope     VARCHAR(32)  NOT NULL,
    decided_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_scope_decision CHECK (decision IN ('INCLUDE', 'EXCLUDE')),
    CONSTRAINT ck_scope_decision_scope CHECK (resulting_scope IN ('INCLUDED', 'EXCLUDED')),
    -- 纳入只可能落在证据齐全的岗位上；应用层已经拦住，这里再兜一层，免得绕过服务直接写库。
    CONSTRAINT ck_scope_decision_include_verified CHECK (
        decision <> 'INCLUDE' OR (resulting_quality = 'VERIFIED' AND resulting_scope = 'INCLUDED'))
);

CREATE INDEX ix_scope_decision_job ON job_admission_scope_decision (job_posting_id, decided_at DESC);

CREATE OR REPLACE FUNCTION forbid_scope_decision_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'job_admission_scope_decision is append-only';
END;
$$;

CREATE TRIGGER trg_scope_decision_append_only
BEFORE UPDATE ON job_admission_scope_decision
FOR EACH ROW
EXECUTE FUNCTION forbid_scope_decision_update();
