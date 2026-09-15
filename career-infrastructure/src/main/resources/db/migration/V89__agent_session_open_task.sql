-- 一轮以追问收场时，还欠着的那件事。
--
-- 没有这两列，用户刷新之后只答一句"余杭"，系统既不知道他原本要办什么，也不知道自己问过
-- 他什么——那一问等于白问，他得从头再说一遍。存的是用户当时那句话和系统问出去的那句话，
-- 不是任何推断出来的意图：推断错了没人看得出来，而原话至少可以被他本人认出来。
--
-- 拿到结果的那一轮会把这两列清空（见 AgentSession#withListing）。不清的话，
-- 已经办完的事会被下一轮当成还欠着的，反复接着做。

ALTER TABLE agent_session
    ADD COLUMN open_task        VARCHAR(500),
    ADD COLUMN pending_question VARCHAR(500);
