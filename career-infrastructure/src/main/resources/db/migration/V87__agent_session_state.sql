-- 一轮对话记住的东西。
--
-- last_job_ids 是顺序敏感的：用户说"第二个怎么样"，指的是他屏幕上那份列表的第二个。
-- 重新排一次名次可能已经变了，拿重新排出来的第二名作答不会有任何外在迹象——所以存成
-- jsonb 数组按位置解析，而不是存一组无序的岗位 ID。
--
-- profile_version 是用户看到那份列表和那些问题时的资料版本。它有两个用途：判断旧序号还
-- 算不算数，以及作为确认写入时乐观版本检查的基准。用"此刻库里的版本"去比，那个检查恒真。
--
-- pending_confirmations 只收对话里能直接回答的标量字段；要凭材料判断的条件不列在这里，
-- 免得用户以为说一句"我是硕士"就算数。

CREATE TABLE agent_session (
    id                    UUID        PRIMARY KEY,
    candidate_profile_id  UUID        NOT NULL REFERENCES candidate_profile (id) ON DELETE CASCADE,
    filter_tier           VARCHAR(16),
    filter_location       VARCHAR(80),
    filter_job_family     VARCHAR(48),
    filter_limit          INTEGER     NOT NULL,
    last_job_ids          JSONB       NOT NULL DEFAULT '[]'::jsonb,
    pending_confirmations JSONB       NOT NULL DEFAULT '[]'::jsonb,
    profile_version       VARCHAR(80) NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_agent_session_limit CHECK (filter_limit BETWEEN 1 AND 20),
    CONSTRAINT ck_agent_session_last_job_ids CHECK (jsonb_typeof(last_job_ids) = 'array'),
    CONSTRAINT ck_agent_session_pending CHECK (jsonb_typeof(pending_confirmations) = 'array')
);

CREATE INDEX ix_agent_session_candidate ON agent_session (candidate_profile_id, updated_at DESC);
