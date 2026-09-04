package com.careeros.domain;

import com.careeros.domain.DomainEnums.EmploymentType;
import com.careeros.domain.DomainEnums.OpportunityTier;
import com.careeros.domain.DomainEnums.OrganizationType;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 按产品需求 §3 把岗位分进 T1 主攻 / T2 核验 / T3 备选 / 排除四池。
 *
 * <p>分层只读取已落库的用工性质与单位性质，不做文本推断——公告没写清楚就进 T2 等人工核验，
 * 而不是猜一个身份。§3 明确要求国企岗位"不得标成事业编"，因此国企只会落到 T3。
 *
 * <p>已知局限：{@link EmploymentType} 目前没有"员额/报备员额"和"校聘"两类（§5 要求区分），
 * 这类岗位现在会以 {@code UNKNOWN} 身份落入 T2 待核验。这是安全方向——它们不会被误标成
 * 事业编——但在枚举补齐前，T2 里会混着两种不同性质的岗位。
 */
public final class OpportunityTierClassifier {
    public static final String VERSION = "phase2-tier-v1";

    /** §3 中可能承载事业编制的单位类型。 */
    private static final Set<OrganizationType> PUBLIC_ORGANIZATIONS = EnumSet.of(
        OrganizationType.GOVERNMENT, OrganizationType.PUBLIC_INSTITUTION,
        OrganizationType.UNIVERSITY, OrganizationType.HOSPITAL, OrganizationType.RESEARCH_INSTITUTE);

    public OpportunityTierAssessment classify(JobPosting job, Organization organization) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(organization, "organization");
        EmploymentType employment = job.employmentType();
        OrganizationType organizationType = organization.organizationType();
        boolean publicOrganization = PUBLIC_ORGANIZATIONS.contains(organizationType);

        return switch (employment) {
            case LABOR_DISPATCH -> result(job, OpportunityTier.EXCLUDED, employment, organizationType, true,
                "劳务派遣属于 §3 默认排除范围");

            case PROJECT_BASED -> result(job, OpportunityTier.EXCLUDED, employment, organizationType, true,
                "项目聘用属于 §3 默认排除的短期项目岗");

            case ESTABLISHMENT -> publicOrganization
                ? result(job, OpportunityTier.T1_ESTABLISHMENT_TARGET, employment, organizationType, true,
                    "用工身份明确为事业编制且单位类型一致，进入 T1 主攻池")
                : result(job, OpportunityTier.T2_IDENTITY_REVIEW, employment, organizationType, false,
                    "岗位写明事业编制，但单位类型为 " + organizationType + " 与之不一致，需要人工复核来源");

            // 人事代理与合同制/编外在公共机构仍可能是稳定岗位，但身份必须逐岗确认；
            // 非公共机构的同类岗位不属于首期目标池。
            case PERSONNEL_AGENCY, CONTRACT -> publicOrganization
                ? result(job, OpportunityTier.T2_IDENTITY_REVIEW, employment, organizationType, true,
                    "公共机构非事业编岗位，稳定性与用工身份需单独核验")
                : soeOrExcluded(job, employment, organizationType);

            case UNKNOWN -> {
                if (organizationType == OrganizationType.STATE_OWNED_ENTERPRISE) {
                    yield result(job, OpportunityTier.T3_STABLE_SOE_BACKUP, employment, organizationType, true,
                        "单位类型为国有企业，进入稳定国企备选池；§3 要求不得标成事业编");
                }
                if (publicOrganization) {
                    yield result(job, OpportunityTier.T2_IDENTITY_REVIEW, employment, organizationType, false,
                        "单位具有公共属性，但公告未给出足够的用工身份结论");
                }
                yield result(job, OpportunityTier.UNKNOWN, employment, organizationType, false,
                    "单位性质与用工身份都不足以进入 T1/T2/T3 任一池");
            }
        };
    }

    private static OpportunityTierAssessment soeOrExcluded(
        JobPosting job, EmploymentType employment, OrganizationType organizationType
    ) {
        return organizationType == OrganizationType.STATE_OWNED_ENTERPRISE
            ? result(job, OpportunityTier.T3_STABLE_SOE_BACKUP, employment, organizationType, true,
                "国企正式技术岗，进入稳定国企备选池；§3 要求不得标成事业编")
            : result(job, OpportunityTier.EXCLUDED, employment, organizationType, true,
                "非公共机构的合同制/编外岗位不属于首期目标池");
    }

    private static OpportunityTierAssessment result(
        JobPosting job, OpportunityTier tier, EmploymentType employment,
        OrganizationType organizationType, boolean evidenceSufficient, String reason
    ) {
        return new OpportunityTierAssessment(
            job.id(), tier, employment, organizationType, evidenceSufficient, reason, job.evidenceIds());
    }
}
