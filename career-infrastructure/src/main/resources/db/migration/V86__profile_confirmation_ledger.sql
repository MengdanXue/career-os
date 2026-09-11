-- 确认回答的台账：幂等与失败恢复。
--
-- 幂等：同一次回答重试时带同一把 idempotency_key，不能第二次改写资料，也不能第二次推高
-- 资料版本。主键就是 (candidate_profile_id, idempotency_key)，重复写入在库里不成立。
--
-- 失败恢复：资料已写入但结论还没重算，是一个必须能恢复的中间态——用户的声明留在库里、
-- 岗位结论还是旧的，是最不该沉默的状态。stage='WRITTEN' 记下这一点，重算成功才推进到
-- 'RECOMPUTED'，重试从重算那一步接着做。
--
-- profile_version_before 是重算的对照基线：要拿写入前那一版资料下的岗位结论作对比，
-- 才能说清这次回答到底改变了什么。
--
-- declared_value 存的是规范化后的声明值，用来识别"同一把钥匙换了个答案"这种调用方错误；
-- 它是本人声明，不是官方核实，证据等级由 candidate_fact_confirmation.source 承载
-- （这条路径恒为 USER_CONFIRMED）。

CREATE TABLE profile_confirmation_ledger (
    candidate_profile_id   UUID        NOT NULL REFERENCES candidate_profile (id) ON DELETE CASCADE,
    idempotency_key        VARCHAR(120) NOT NULL,
    fact_key               VARCHAR(64) NOT NULL,
    declared_value         VARCHAR(200) NOT NULL,
    stage                  VARCHAR(16) NOT NULL,
    profile_version_before VARCHAR(80) NOT NULL,
    profile_version_after  VARCHAR(80) NOT NULL,
    recorded_at            TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (candidate_profile_id, idempotency_key),
    CONSTRAINT ck_profile_confirmation_stage CHECK (stage IN ('WRITTEN', 'RECOMPUTED')),
    -- 只有对话里能直接回答的标量字段可以走这条路。学历、工作经历这类要凭材料判断的字段
    -- 不在其中：一句"我有的"不能成为硬资格依据。
    CONSTRAINT ck_profile_confirmation_fact_key CHECK (fact_key IN (
        'GENDER', 'POLITICAL_AFFILIATION',
        'EMPLOYER_SETTLEMENT_AT_APPLICATION', 'SOCIAL_INSURANCE_AT_APPLICATION'
    ))
);

CREATE INDEX ix_profile_confirmation_ledger_candidate
    ON profile_confirmation_ledger (candidate_profile_id, recorded_at DESC);
