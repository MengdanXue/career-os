-- 产品需求 §6.2：禁止把机会压成一个不可解释的匹配分。
-- opportunity.match_score 正是该节点名不要的东西，改为存六维评分与策略等级。
--
-- 历史 match_score 不做迁移保留：它由 V6 之前的 EligibilityStatus 语义算出，
-- 那套语义已经废弃，旧分值无法映射到任何一维，留着只会被误读成结论。

ALTER TABLE opportunity
    ADD COLUMN scorecard JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN strategy_grade VARCHAR(32);

-- 已有记录没有六维依据，一律标为待核验，而不是猜一个等级。
UPDATE opportunity SET strategy_grade = 'VERIFY_FIRST' WHERE strategy_grade IS NULL;

ALTER TABLE opportunity
    ALTER COLUMN strategy_grade SET NOT NULL,
    ADD CONSTRAINT opportunity_strategy_grade_check
        CHECK (strategy_grade IN ('MUST_TRACK','APPLY','VERIFY_FIRST','BACKUP','REJECT')),
    ADD CONSTRAINT opportunity_scorecard_check
        CHECK (jsonb_typeof(scorecard) = 'object');

ALTER TABLE opportunity DROP COLUMN match_score;
