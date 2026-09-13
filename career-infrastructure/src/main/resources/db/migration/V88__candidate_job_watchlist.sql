-- 用户自己盯着的岗位。
--
-- last_seen_status 是"用户最后一次看到的硬资格结论"，不是"最新结论"。它与当前结论的差别
-- 就是用户回来要看的东西，所以只能由用户确实查看之后显式推进。让重算或者列表查询顺手刷新
-- 它，代码更短、清单永远最新——代价是岗位从待确认变成不可报这件事，用户永远不会知道。
-- 变化信号只有一次，抹掉就没了。
--
-- 允许为空：刚关注还没看过任何结论。那是"第一次看到"，不是"变了"。
--
-- 关注不是报名。这张表只记录"我在盯着它"，没有任何列表示已提交报名——系统不代替用户报名。

CREATE TABLE candidate_job_watch (
    candidate_profile_id       UUID        NOT NULL REFERENCES candidate_profile (id) ON DELETE CASCADE,
    job_posting_id             UUID        NOT NULL REFERENCES job_posting (id) ON DELETE CASCADE,
    last_seen_status           VARCHAR(32),
    last_seen_evaluator_version VARCHAR(160),
    watched_at                 TIMESTAMPTZ NOT NULL,
    last_seen_at               TIMESTAMPTZ,
    PRIMARY KEY (candidate_profile_id, job_posting_id),
    CONSTRAINT ck_candidate_job_watch_status CHECK (last_seen_status IS NULL OR last_seen_status IN (
        'ELIGIBLE', 'CONDITIONAL', 'NEEDS_CONFIRMATION', 'CONFLICTING_EVIDENCE', 'INELIGIBLE'
    )),
    -- 看过就必须有看过的时间，否则"上次看到"无从追溯。
    CONSTRAINT ck_candidate_job_watch_seen CHECK (
        (last_seen_status IS NULL AND last_seen_at IS NULL)
        OR (last_seen_status IS NOT NULL AND last_seen_at IS NOT NULL)
    )
);

CREATE INDEX ix_candidate_job_watch_candidate ON candidate_job_watch (candidate_profile_id, watched_at DESC);
