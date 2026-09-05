package com.careeros.domain;

public final class DomainEnums {
    private DomainEnums() {}

    public enum EventType { PUBLIC_INSTITUTION, STATE_OWNED_ENTERPRISE, UNIVERSITY, HOSPITAL, GOVERNMENT_PURCHASED_SERVICE, OTHER }
    public enum OrganizationType { GOVERNMENT, PUBLIC_INSTITUTION, STATE_OWNED_ENTERPRISE, UNIVERSITY, HOSPITAL, RESEARCH_INSTITUTE, OTHER, UNKNOWN }
    /**
     * 产品需求 §5：事业编、员额/报备员额、校聘、国企正式、合同制/编外、劳务派遣、未知必须能分开表达。
     * PROJECT_BASED（项目聘用）保留，§3 把它列入默认排除。
     */
    public enum EmploymentType {
        ESTABLISHMENT, AUTHORIZED_HEADCOUNT, SCHOOL_HIRED, STATE_OWNED_REGULAR,
        PERSONNEL_AGENCY, LABOR_DISPATCH, CONTRACT, PROJECT_BASED, UNKNOWN
    }
    public enum JobFamily { SOFTWARE, DATA, AI, CYBERSECURITY, INFORMATION_SYSTEMS, DIGITALIZATION, IT_OPERATIONS, RESEARCH, PRODUCT, OTHER }
    public enum EducationLevel { UNKNOWN, HIGH_SCHOOL, ASSOCIATE, BACHELOR, MASTER, DOCTORATE }
    public enum RuleType { AGE, EDUCATION, DEGREE, EXACT_MAJOR, GRADUATE_YEAR, EXPERIENCE, PROFESSIONAL_TITLE, POLITICAL_STATUS, APPLICANT_TYPE, OTHER_CONDITIONS, OTHER }
    /** 产品需求 §6.1：资格是硬判定，不是分数。禁止引入 LIKELY_* 之类的程度值。 */
    public enum EligibilityStatus { ELIGIBLE, INELIGIBLE, CONDITIONAL, NEEDS_CONFIRMATION, CONFLICTING_EVIDENCE }
    public enum CriterionStatus { PASS, FAIL, CONDITIONAL, UNKNOWN, NOT_APPLICABLE }
    /** 产品需求 §3：不同用工性质必须分池，不能只给一个"半体制"标签。 */
    public enum OpportunityTier { T1_ESTABLISHMENT_TARGET, T2_IDENTITY_REVIEW, T3_STABLE_SOE_BACKUP, EXCLUDED, UNKNOWN }
    /** 产品需求 §6.2 的六个维度，分别计算、分别呈现，禁止合成一个匹配分。 */
    public enum ScoreDimension { FIT, CHANCE, STABILITY, GROWTH, FUTURE, PREPARATION_COST }
    /** 每一维的分数是怎么来的。§6.2 要求估计值必须自报为估计，且数据不足时不得编分。 */
    public enum ScoreBasis { MEASURED, ESTIMATED, INSUFFICIENT_DATA }
    /**
     * 跨年度再现信号。产品需求 §11 把"没有历史样本支撑的精确上岸概率"列为非目标，
     * 因此这里只给可观测的模式，不给概率数字。
     */
    public enum RecurrenceSignal { RECURRING_ANNUAL, INTERMITTENT, SINGLE_OCCURRENCE, INSUFFICIENT_HISTORY }
    /** §6.2：六维之上只输出策略等级，由规则推导而非阈值加权。 */
    public enum StrategyGrade { MUST_TRACK, APPLY, VERIFY_FIRST, BACKUP, REJECT }
    public enum EvidenceType { OFFICIAL_NOTICE, OFFICIAL_ATTACHMENT, ORGANIZATION_PAGE, POLICY, MANUAL_NOTE }
    public enum OpportunityStatus { NEW, REVIEWING, SHORTLISTED, APPLIED, CLOSED, REJECTED, ARCHIVED }
    public enum ExtractionSourceType { HTML, PDF }
    public enum DataQualityStatus { RAW, PARSED, NORMALIZED, REVIEW_REQUIRED, VERIFIED, REJECTED, FAILED }
    public enum ParserQuality { ACCEPTABLE, LOW_TEXT_QUALITY }
    public enum FactStatus { EXPLICIT, INTERPRETED, UNKNOWN }
    public enum ReviewStatus { PENDING, RESOLVED }
    public enum ReviewDecision { CONFIRM, CORRECT, REJECT, NEED_MORE_EVIDENCE }
    public enum ReviewReasonCode {
        LOW_CONFIDENCE, MISSING_EVIDENCE, ORGANIZATION_TYPE_UNKNOWN,
        CONFLICTING_SOURCES, PARSER_FAILURE, LOW_TEXT_QUALITY,
        SCHEMA_INVALID, RESTRICTIVE_FACT_FROM_LLM
    }
    /**
     * 岗位上可以逐字段追溯到证据片段的硬条件字段。产品需求 §10.6 要求"每个资格结论、
     * 用工身份和关键推荐都有可定位证据"——公告级证据不够，必须落到片段。
     */
    public enum JobField {
        TITLE, HEADCOUNT, EMPLOYMENT_TYPE, MINIMUM_EDUCATION, DEGREE, MAJOR_TEXT,
        MAXIMUM_AGE, ACCEPTED_GRADUATION_YEARS, MINIMUM_EXPERIENCE_YEARS
    }
    public enum LocatorType { HTML, PDF }
}
