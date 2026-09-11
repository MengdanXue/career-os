package com.careeros.domain;

public final class DomainEnums {
    private DomainEnums() {}

    public enum EventType { PUBLIC_INSTITUTION, STATE_OWNED_ENTERPRISE, UNIVERSITY, HOSPITAL, GOVERNMENT_PURCHASED_SERVICE, OTHER }
    public enum OrganizationType { GOVERNMENT, PUBLIC_INSTITUTION, STATE_OWNED_ENTERPRISE, UNIVERSITY, HOSPITAL, RESEARCH_INSTITUTE, OTHER, UNKNOWN }
    public enum EmploymentType {
        ESTABLISHMENT, QUOTA_OR_FILING, PUBLIC_INSTITUTION_FORMAL, UNIT_FORMAL, SOE_FORMAL,
        PERSONNEL_AGENCY, LABOR_DISPATCH, CONTRACT, PROJECT_BASED, UNKNOWN
    }
    public enum JobFamily { SOFTWARE, DATA, AI, CYBERSECURITY, INFORMATION_SYSTEMS, DIGITALIZATION, IT_OPERATIONS, RESEARCH, PRODUCT, OTHER }
    public enum EducationLevel { UNKNOWN, HIGH_SCHOOL, ASSOCIATE, BACHELOR, MASTER, DOCTORATE }
    public enum Gender { FEMALE, MALE, OTHER, UNKNOWN }
    public enum PoliticalAffiliation { CPC_MEMBER, CPC_PROBATIONARY, NON_MEMBER, UNKNOWN }
    public enum RuleType { AGE, EDUCATION, EXACT_MAJOR, GRADUATE_YEAR, EXPERIENCE, PROFESSIONAL_TITLE, POLITICAL_AFFILIATION, GENDER, OTHER }
    /**
     * 硬资格判定结果（产品需求 §6.1）。
     *
     * <p>刻意没有“大概可报”这一档：资格是硬判定，要么有证据满足，要么明确不满足，
     * 要么说清楚缺什么。此前的 LIKELY_ELIGIBLE / LIKELY_INELIGIBLE 让“证据不足”
     * 看起来像一个偏向性结论，正是基线禁止的那种表述。
     *
     * <ul>
     *   <li>{@code ELIGIBLE}：所有硬条件都有已确认事实且满足；
     *   <li>{@code CONDITIONAL}：满足与否只取决于一件尚未完成的事，例如留服认证或预计毕业；
     *   <li>{@code NEEDS_CONFIRMATION}：公告或个人字段不足，无法判定；
     *   <li>{@code CONFLICTING_EVIDENCE}：该条所依赖的官方字段存在来源冲突，判定不可信；
     *   <li>{@code INELIGIBLE}：至少一项硬条件明确不满足。
     * </ul>
     *
     * 只有 ELIGIBLE 和经用户确认后的 CONDITIONAL 才能进入“建议报名”。
     */
    public enum EligibilityStatus { ELIGIBLE, CONDITIONAL, NEEDS_CONFIRMATION, CONFLICTING_EVIDENCE, INELIGIBLE }
    public enum EvidenceType { OFFICIAL_NOTICE, OFFICIAL_ATTACHMENT, ORGANIZATION_PAGE, POLICY, MANUAL_NOTE }
    public enum OpportunityStatus { NEW, REVIEWING, SHORTLISTED, APPLIED, CLOSED, REJECTED, ARCHIVED }
    public enum ExtractionSourceType { HTML, PDF, DOCX }
    public enum DataQualityStatus { RAW, PARSED, NORMALIZED, REVIEW_REQUIRED, VERIFIED, REJECTED, FAILED }
    public enum TargetScopeStatus { INCLUDED, EXCLUDED, NEEDS_REVIEW }
    public enum JobAdmissionReason {
        LEGACY_UNVERIFIED, NOT_CLASSIFIED, CONTENT_CHANGED, TARGET_TECHNICAL_ROLE,
        DOCTOR_REQUIRED, TEACHING_ROLE, POSTDOCTORAL_ROLE, ADMINISTRATIVE_ROLE,
        SALES_ROLE, LABOR_DISPATCH, PROJECT_BASED, INTERNSHIP,
        NON_TECHNICAL_ROLE, AMBIGUOUS_DUTIES, EMPLOYMENT_IDENTITY_UNKNOWN,
        OFFICIAL_WORKBOOK_PARSED, OFFICIAL_FACTS_INCOMPLETE, MISSING_FIELD_EVIDENCE,
        /** 官方来源在某个字段上互相矛盾。此前只体现为 REVIEW_REQUIRED，读不出原因。 */
        CONFLICTING_FIELD_EVIDENCE
    }
    public enum ParserQuality { ACCEPTABLE, LOW_TEXT_QUALITY }
    public enum FactStatus { EXPLICIT, INTERPRETED, UNKNOWN }
    public enum ReviewStatus { PENDING, RESOLVED }
    public enum ReviewDecision { CONFIRM, CORRECT, REJECT, NEED_MORE_EVIDENCE }
    public enum ReviewReasonCode {
        LOW_CONFIDENCE, MISSING_EVIDENCE, ORGANIZATION_TYPE_UNKNOWN,
        CONFLICTING_SOURCES, PARSER_FAILURE, LOW_TEXT_QUALITY,
        SCHEMA_INVALID, RESTRICTIVE_FACT_FROM_LLM,
        /** 送入模型的文档被预算截断，抽取结果只覆盖部分原文，必须人工复核。 */
        INPUT_TRUNCATED
    }
    public enum LocatorType { HTML, PDF, DOCX }
    public enum AssessmentFactStatus { EXPLICIT, INTERPRETED, UNKNOWN }
    public enum AssessmentDimensionType {
        MAJOR_FIT, SKILL_FIT, EXPERIENCE_FIT, RESEARCH_FIT, PROFESSIONAL_TITLE_FIT, PREFERENCE_FIT,
        EMPLOYMENT_SECURITY, FUNDING_STABILITY, ORGANIZATION_STABILITY, POLICY_STABILITY,
        BUSINESS_VOLATILITY, LAYOFF_RISK, CONTRACT_RISK
    }
    /**
     * 跨年度再现信号。产品需求 §11 把“没有历史样本支撑的精确上岸概率”列为非目标，
     * 因此这里只给可观测的模式，不给概率数字。
     */
    public enum RecurrenceSignal { RECURRING_ANNUAL, INTERMITTENT, SINGLE_OCCURRENCE, INSUFFICIENT_HISTORY }
    /** 一次增量入库里单个岗位的变化类型。 */
    public enum JobChangeKind { NEW, UPDATED, UNCHANGED, DEACTIVATED }
    /** 产品需求 §10.7：每日只报新增、变更、下线和截止临近，四者之外一律不推送。 */
    public enum DigestReason { NEW, UPDATED, DEACTIVATED, DEADLINE_APPROACHING }
    public enum OpportunityTier { T1, T2, T3, EXCLUDED }
    public enum RecommendationStatus { RECOMMENDED, REVIEW, NOT_RECOMMENDED, EXCLUDED }
}
