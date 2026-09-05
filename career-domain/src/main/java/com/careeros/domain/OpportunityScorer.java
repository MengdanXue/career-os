package com.careeros.domain;

import com.careeros.domain.DomainEnums.CriterionStatus;
import com.careeros.domain.DomainEnums.EligibilityStatus;
import com.careeros.domain.DomainEnums.EmploymentType;
import com.careeros.domain.DomainEnums.JobFamily;
import com.careeros.domain.DomainEnums.OpportunityTier;
import com.careeros.domain.DomainEnums.RuleType;
import com.careeros.domain.DomainEnums.ScoreDimension;
import com.careeros.domain.DomainEnums.StrategyGrade;
import com.careeros.domain.OpportunityScorecard.DimensionScore;
import java.util.Map;
import java.util.Objects;

/**
 * 产品需求 §6.2 的多维评分。六个维度分别算、分别存，**不做加权求和**——
 * 策略等级由资格结论与分池结论按规则推导，分数只用于在同一等级内区分优先级。
 *
 * <p>§6.2 要求的六个输入里，"已知竞争数据"（CompetitionObservation）尚未建模，考试形式与报名
 * 时间窗口也还不在 {@link JobPosting} 上。因此 {@code PREPARATION_COST} 恒为 INSUFFICIENT_DATA，
 * {@code CHANCE} 只能给出估计。{@code FUTURE} 依赖 {@link OpportunityForecast}——未回填跨年度
 * 历史时同样是 INSUFFICIENT_DATA。这些缺口如实写在各自的 rationale 里，而不是用默认分掩盖。
 */
public final class OpportunityScorer {
    public static final String VERSION = "phase2-scorecard-v1";

    /** T1 岗位要进 MUST_TRACK 所需的最低匹配度；低于此值仍是 APPLY，不因分池自动升级。 */
    static final int MUST_TRACK_FIT_THRESHOLD = 60;

    public OpportunityScorecard score(
        CandidateProfile candidate,
        JobPosting job,
        Organization organization,
        EligibilityAssessment assessment,
        OpportunityTierAssessment tier
    ) {
        return score(candidate, job, organization, assessment, tier, null);
    }

    /** @param forecast 跨年度再现信号，未回填历史时传 null——此时 FUTURE 维度保持"数据不足"。 */
    public OpportunityScorecard score(
        CandidateProfile candidate,
        JobPosting job,
        Organization organization,
        EligibilityAssessment assessment,
        OpportunityTierAssessment tier,
        OpportunityForecast forecast
    ) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(organization, "organization");
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(tier, "tier");

        var dimensions = OpportunityScorecard.emptyDimensions();
        DimensionScore fit = fit(candidate, job, assessment);
        dimensions.put(ScoreDimension.FIT, fit);
        dimensions.put(ScoreDimension.CHANCE, chance(job));
        dimensions.put(ScoreDimension.STABILITY, stability(job, tier));
        dimensions.put(ScoreDimension.GROWTH, growth(job));
        dimensions.put(ScoreDimension.FUTURE, future(forecast));
        dimensions.put(ScoreDimension.PREPARATION_COST, DimensionScore.insufficient(
            "岗位模型尚未包含考试形式、专业测试与报名时间窗口，无法估算准备成本"));

        Grade grade = grade(assessment.status(), tier, fit);
        return new OpportunityScorecard(job.id(), dimensions, grade.value(), grade.rationale(), VERSION);
    }

    /**
     * 再现信号转成分值。§11 禁止给出概率，因此这里只是把可观测的模式排个序，
     * 并把观测窗口原样带进 rationale——读者要能看出结论建立在多长的历史上。
     */
    static DimensionScore future(OpportunityForecast forecast) {
        if (forecast == null) {
            return DimensionScore.insufficient("尚未回填跨年度招聘历史，无法评估再现信号");
        }
        return switch (forecast.signal()) {
            case INSUFFICIENT_HISTORY -> DimensionScore.insufficient(forecast.rationale());
            case RECURRING_ANNUAL -> DimensionScore.estimated(85, forecast.rationale());
            case INTERMITTENT -> DimensionScore.estimated(50, forecast.rationale());
            case SINGLE_OCCURRENCE -> DimensionScore.estimated(20, forecast.rationale());
        };
    }

    /**
     * 策略等级的推导顺序即优先级，与资格聚合一样 fail-closed：不可报与被排除先出局，
     * 任何未确认项都停在 VERIFY_FIRST，只有资格与身份都站得住才按分池给出行动建议。
     */
    static Grade grade(EligibilityStatus eligibility, OpportunityTierAssessment tier, DimensionScore fit) {
        if (eligibility == EligibilityStatus.INELIGIBLE) {
            return new Grade(StrategyGrade.REJECT, "硬条件明确不满足，不可报");
        }
        if (eligibility == EligibilityStatus.CONFLICTING_EVIDENCE) {
            return new Grade(StrategyGrade.REJECT, "来源之间证据冲突，先解决冲突再谈是否可报");
        }
        if (tier.tier() == OpportunityTier.EXCLUDED) {
            return new Grade(StrategyGrade.REJECT, "用工性质属于 §3 默认排除范围：" + tier.reason());
        }
        if (eligibility == EligibilityStatus.NEEDS_CONFIRMATION) {
            return new Grade(StrategyGrade.VERIFY_FIRST, "仍有未确认的硬条件，确认后才能判断是否值得投入");
        }
        if (eligibility == EligibilityStatus.CONDITIONAL) {
            return new Grade(StrategyGrade.VERIFY_FIRST, "资格取决于尚未完成的事项，需先确认前提");
        }
        if (!tier.evidenceSufficient() || tier.tier() == OpportunityTier.UNKNOWN) {
            return new Grade(StrategyGrade.VERIFY_FIRST, "用工身份证据不足：" + tier.reason());
        }
        return switch (tier.tier()) {
            case T1_ESTABLISHMENT_TARGET -> fit.value() != null && fit.value() >= MUST_TRACK_FIT_THRESHOLD
                ? new Grade(StrategyGrade.MUST_TRACK, "事业编岗位且岗位内容匹配度达标，列入主攻")
                : new Grade(StrategyGrade.APPLY, "事业编岗位，但岗位内容匹配度一般，可报不主攻");
            case T2_IDENTITY_REVIEW -> new Grade(StrategyGrade.APPLY, "身份已确认的公共机构岗位，可报");
            case T3_STABLE_SOE_BACKUP -> new Grade(StrategyGrade.BACKUP, "稳定国企岗位，作为备选");
            case EXCLUDED, UNKNOWN -> throw new IllegalStateException("已在前置分支处理：" + tier.tier());
        };
    }

    /** 专业与岗位内容的匹配。专业是否命中直接取自资格判定，避免同一件事算两遍且口径不一。 */
    private static DimensionScore fit(CandidateProfile candidate, JobPosting job, EligibilityAssessment assessment) {
        int score = 0;
        var reasons = new StringBuilder();
        CriterionStatus major = statusOf(assessment, RuleType.EXACT_MAJOR);
        if (major == CriterionStatus.PASS) {
            score += 45; reasons.append("专业精确命中岗位目录；");
        } else if (major == CriterionStatus.CONDITIONAL) {
            score += 25; reasons.append("专业属门类范围，等同性待确认；");
        } else if (major == CriterionStatus.NOT_APPLICABLE) {
            score += 30; reasons.append("岗位未限定专业；");
        } else {
            reasons.append("专业匹配未确认；");
        }
        if (job.jobFamily() != JobFamily.OTHER) {
            score += 30; reasons.append("岗位属于 ").append(job.jobFamily()).append(" 技术族；");
        } else {
            reasons.append("岗位技术族不明确；");
        }
        if (!candidate.preferredLocations().isEmpty() && job.location() != null
            && candidate.preferredLocations().stream().anyMatch(job.location()::contains)) {
            score += 25; reasons.append("工作地点在偏好范围内；");
        } else {
            reasons.append("工作地点不在偏好范围或未知；");
        }
        // 专业与技术族都来自已核对的岗位字段，属于可测量输入；经历深度与岗位职责的贴合度仍靠文本，
        // 未纳入，因此这一维标记为估计而不是测量。
        return DimensionScore.estimated(Math.min(score, 100), reasons.toString());
    }

    /** §6.2 要的四个输入只拿到两个：招聘人数与专业限制宽度。 */
    private static DimensionScore chance(JobPosting job) {
        int score = Math.min(job.headcount() * 15, 50);
        String headcountReason = "招聘 " + job.headcount() + " 人；";
        String breadthReason;
        if (job.exactMajors().isEmpty()) {
            score += 15; breadthReason = "岗位未限定专业，竞争面较宽；";
        } else if (job.exactMajors().size() >= 5) {
            score += 25; breadthReason = "允许专业较多（" + job.exactMajors().size() + " 个）；";
        } else {
            score += 35; breadthReason = "允许专业较窄（" + job.exactMajors().size() + " 个），符合条件者更少；";
        }
        return DimensionScore.estimated(Math.min(score, 100),
            headcountReason + breadthReason + "缺少开考比例与报名人数，未计入考试形式与已知竞争数据");
    }

    /** 稳定性的主要依据是用工身份，这一项是已核对的事实；岗位持续性仍缺历史数据。 */
    private static DimensionScore stability(JobPosting job, OpportunityTierAssessment tier) {
        int score = switch (job.employmentType()) {
            case ESTABLISHMENT -> 95;
            case AUTHORIZED_HEADCOUNT -> 75;
            case SCHOOL_HIRED -> 65;
            case STATE_OWNED_REGULAR -> 60;
            case PERSONNEL_AGENCY -> 40;
            case CONTRACT -> 35;
            case PROJECT_BASED -> 15;
            case LABOR_DISPATCH -> 5;
            case UNKNOWN -> 25;
        };
        return DimensionScore.estimated(score,
            "用工身份 " + job.employmentType() + "，单位类型 " + tier.organizationType()
                + "；缺少岗位跨年度持续性记录，未计入持续性");
    }

    /** 技术发挥看技术族，职称路线看岗位是否设职称门槛；责任边界仍只有自由文本，未纳入。 */
    private static DimensionScore growth(JobPosting job) {
        int score = switch (job.jobFamily()) {
            case AI, DATA, SOFTWARE -> 80;
            case INFORMATION_SYSTEMS, DIGITALIZATION, CYBERSECURITY -> 65;
            case RESEARCH, PRODUCT -> 55;
            case IT_OPERATIONS -> 45;
            case OTHER -> 25;
        };
        String titleReason = job.requiredProfessionalTitles().isEmpty()
            ? "岗位未设职称门槛，职称路线不明确；"
            : "岗位设有职称要求，存在明确职称路线；";
        if (!job.requiredProfessionalTitles().isEmpty()) score = Math.min(score + 10, 100);
        return DimensionScore.estimated(score,
            "技术族 " + job.jobFamily() + "；" + titleReason + "责任边界仅有自由文本描述，未纳入");
    }

    private static CriterionStatus statusOf(EligibilityAssessment assessment, RuleType rule) {
        var result = assessment.ruleResults().get(rule);
        return result == null ? CriterionStatus.UNKNOWN : result.status();
    }

    record Grade(StrategyGrade value, String rationale) {}
}
