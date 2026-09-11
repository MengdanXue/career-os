-- 硬资格判定语义改造（产品需求 §6.1）。
--
-- 旧枚举 ELIGIBLE / LIKELY_ELIGIBLE / UNCERTAIN / LIKELY_INELIGIBLE / INELIGIBLE 里，
-- 两个 LIKELY_* 把“证据不足”表述成了一个带倾向的结论，正是基线禁止的那种说法。
-- 新枚举为 ELIGIBLE / CONDITIONAL / NEEDS_CONFIRMATION / CONFLICTING_EVIDENCE / INELIGIBLE。
--
-- 旧行迁移：LIKELY_ELIGIBLE、LIKELY_INELIGIBLE、UNCERTAIN 一律并入 NEEDS_CONFIRMATION。
-- INELIGIBLE 按基线要求“至少一项硬条件明确不满足”，LIKELY_INELIGIBLE 够不上这个标准，
-- 因此不并入 INELIGIBLE——宁可多一条待确认，也不静默丢掉一个其实可报的岗位。
-- CONDITIONAL 和 CONFLICTING_EVIDENCE 都需要旧版判定没有采集过的输入（学历认证状态、
-- 官方字段冲突集），无法从历史数据反推，因此不回填，留给新版判定重新评估。

UPDATE eligibility_assessment
   SET status = 'NEEDS_CONFIRMATION'
 WHERE status IN ('LIKELY_ELIGIBLE', 'LIKELY_INELIGIBLE', 'UNCERTAIN');

-- 逐条规则结果存在 rule_results JSONB 里，形如
-- {"AGE": {"status": "UNCERTAIN", "explanation": "..."}}。
-- 这里不能只改总状态：读取时对每一条都会做 EligibilityStatus.valueOf()，
-- 漏掉一条就会让整行读不出来。
UPDATE eligibility_assessment
   SET rule_results = (
           SELECT jsonb_object_agg(
                      rule_key,
                      CASE
                          WHEN rule_value ->> 'status'
                               IN ('LIKELY_ELIGIBLE', 'LIKELY_INELIGIBLE', 'UNCERTAIN')
                          THEN jsonb_set(rule_value, '{status}', '"NEEDS_CONFIRMATION"')
                          ELSE rule_value
                      END)
             FROM jsonb_each(rule_results) AS entries(rule_key, rule_value))
 WHERE EXISTS (
           SELECT 1
             FROM jsonb_each(rule_results) AS entries(rule_key, rule_value)
            WHERE rule_value ->> 'status'
                  IN ('LIKELY_ELIGIBLE', 'LIKELY_INELIGIBLE', 'UNCERTAIN'));

-- decision_assessment.eligibility_status 带一个内联 CHECK，名字由 Postgres 自动生成，
-- 因此按表和列查出来再删，不硬编码名字。
DO $$
DECLARE constraint_name TEXT;
BEGIN
    FOR constraint_name IN
        SELECT c.conname
          FROM pg_constraint c
         WHERE c.conrelid = 'decision_assessment'::regclass
           AND c.contype = 'c'
           AND pg_get_constraintdef(c.oid) LIKE '%eligibility_status%'
    LOOP
        EXECUTE format('ALTER TABLE decision_assessment DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;

UPDATE decision_assessment
   SET eligibility_status = 'NEEDS_CONFIRMATION'
 WHERE eligibility_status IN ('LIKELY_ELIGIBLE', 'LIKELY_INELIGIBLE', 'UNCERTAIN');

ALTER TABLE decision_assessment
    ADD CONSTRAINT ck_decision_assessment_eligibility_status
    CHECK (eligibility_status IN (
        'ELIGIBLE', 'CONDITIONAL', 'NEEDS_CONFIRMATION', 'CONFLICTING_EVIDENCE', 'INELIGIBLE'));

-- eligibility_assessment.status 此前没有 CHECK，新语义下补上：这一列直接决定
-- 一个岗位会不会进入“建议报名”，写进去一个拼错的值不该等到读取时才炸。
ALTER TABLE eligibility_assessment
    ADD CONSTRAINT ck_eligibility_assessment_status
    CHECK (status IN (
        'ELIGIBLE', 'CONDITIONAL', 'NEEDS_CONFIRMATION', 'CONFLICTING_EVIDENCE', 'INELIGIBLE'));
