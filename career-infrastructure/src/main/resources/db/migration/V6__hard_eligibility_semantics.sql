-- 产品需求 §6.1：资格是硬判定，不是分数。
-- EligibilityStatus 从 {ELIGIBLE, LIKELY_ELIGIBLE, UNCERTAIN, LIKELY_INELIGIBLE, INELIGIBLE}
-- 改为 {ELIGIBLE, INELIGIBLE, CONDITIONAL, NEEDS_CONFIRMATION, CONFLICTING_EVIDENCE}。
-- 迁移方向 fail-closed：任何非硬结论的旧值一律降级为 NEEDS_CONFIRMATION，绝不升级为 ELIGIBLE。

ALTER TABLE eligibility_assessment
    ADD COLUMN required_confirmations JSONB NOT NULL DEFAULT '[]'::jsonb;

UPDATE eligibility_assessment
SET status = CASE status
    WHEN 'ELIGIBLE' THEN 'ELIGIBLE'
    WHEN 'INELIGIBLE' THEN 'INELIGIBLE'
    ELSE 'NEEDS_CONFIRMATION'
END;

ALTER TABLE eligibility_assessment
    ADD CONSTRAINT eligibility_assessment_status_check
    CHECK (status IN ('ELIGIBLE','INELIGIBLE','CONDITIONAL','NEEDS_CONFIRMATION','CONFLICTING_EVIDENCE'));

-- rule_results 的逐条状态从 EligibilityStatus 改为 CriterionStatus，并把 explanation 更名为 reason。
UPDATE eligibility_assessment
SET rule_results = (
    SELECT COALESCE(jsonb_object_agg(key, jsonb_build_object(
        'status', CASE value ->> 'status'
            WHEN 'ELIGIBLE' THEN 'PASS'
            WHEN 'INELIGIBLE' THEN 'FAIL'
            ELSE 'UNKNOWN'
        END,
        'reason', COALESCE(value ->> 'explanation', value ->> 'reason', '历史评估，无原始理由')
    )), '{}'::jsonb)
    FROM jsonb_each(rule_results)
)
WHERE jsonb_typeof(rule_results) = 'object';

ALTER TABLE eligibility_assessment
    ADD CONSTRAINT eligibility_assessment_required_confirmations_check
    CHECK (jsonb_typeof(required_confirmations) = 'array');
