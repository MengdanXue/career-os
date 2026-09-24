-- Agent 运行台账。
--
-- 在此之前 /agent-runs 的轨迹只存在于那一次 HTTP 应答里：事后既审计不了"这一步是模型选的还是写死的"，
-- 也回放不了模型当时到底说了什么。v1.0 要求每一步工具调用和证据来源都落库。
--
-- 三张表，整份运行一次写入：
--   agent_run         一次运行：谁问的、问了什么、哪个规划器、结局、渲染出的回答或追问、被拒原因、预算
--   agent_run_step    每一步：工具、参数、理由、是否被执行器接受、拒绝原因、预算、观测摘要与数据、证据引用
--   agent_model_turn  模型规划器的每一轮：提示哈希、发给模型的状态、模型原样返回的文字（可据此回放）

CREATE TABLE agent_run (
    id                   UUID         PRIMARY KEY,
    candidate_profile_id UUID         NOT NULL REFERENCES candidate_profile (id) ON DELETE CASCADE,
    session_id           UUID,
    user_question        TEXT         NOT NULL,
    planner_identity     VARCHAR(200) NOT NULL,
    outcome              VARCHAR(32)  NOT NULL,
    narrative            TEXT,
    asked_question       TEXT,
    violations           JSONB        NOT NULL DEFAULT '[]'::jsonb,
    grounded_on          JSONB        NOT NULL DEFAULT '[]'::jsonb,
    budget_spent         INTEGER      NOT NULL,
    budget_limit         INTEGER      NOT NULL,
    started_at           TIMESTAMPTZ  NOT NULL,
    finished_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_agent_run_outcome CHECK (outcome IN
        ('FINISHED', 'ASKED_USER', 'BUDGET_EXHAUSTED', 'STEP_LIMIT_REACHED', 'PLANNER_FAILED'))
);

CREATE INDEX ix_agent_run_candidate ON agent_run (candidate_profile_id, started_at DESC);

CREATE TABLE agent_run_step (
    run_id              UUID        NOT NULL REFERENCES agent_run (id) ON DELETE CASCADE,
    step_index          INTEGER     NOT NULL,
    tool                VARCHAR(200) NOT NULL,
    arguments           JSONB       NOT NULL DEFAULT '{}'::jsonb,
    why                 TEXT,
    accepted            BOOLEAN     NOT NULL,
    reason              TEXT,
    budget_units        INTEGER     NOT NULL,
    observation_index   INTEGER,
    observation_ok      BOOLEAN,
    observation_summary TEXT,
    observation_data    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    evidence            JSONB       NOT NULL DEFAULT '[]'::jsonb,
    PRIMARY KEY (run_id, step_index)
);

CREATE TABLE agent_model_turn (
    run_id        UUID    NOT NULL REFERENCES agent_run (id) ON DELETE CASCADE,
    turn_index    INTEGER NOT NULL,
    prompt_sha256 CHAR(64),
    state_text    TEXT,
    raw_response  TEXT,
    PRIMARY KEY (run_id, turn_index)
);
