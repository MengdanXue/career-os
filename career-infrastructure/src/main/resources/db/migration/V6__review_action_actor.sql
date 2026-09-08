-- 人工复核是把模型提案提升为已核验官方数据的唯一闸门，
-- 但此前 review_action 只记录了决定和时间，没有记录是谁做的。

ALTER TABLE review_action ADD COLUMN actor TEXT;

-- V6 之前的动作无法追溯到具体操作人，明确标记而不是留空。
UPDATE review_action SET actor = 'unknown-legacy' WHERE actor IS NULL;

ALTER TABLE review_action ALTER COLUMN actor SET NOT NULL;

ALTER TABLE review_action
    ADD CONSTRAINT review_action_actor_not_blank CHECK (length(trim(actor)) > 0);

CREATE INDEX idx_review_action_actor ON review_action(actor, acted_at DESC);
