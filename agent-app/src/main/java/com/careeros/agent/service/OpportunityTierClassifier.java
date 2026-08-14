package com.careeros.agent.service;

import com.careeros.agent.domain.OpportunityTierAssessment;
import com.careeros.agent.domain.OpportunityTierAssessment.Evidence;
import com.careeros.agent.domain.OpportunityTierAssessment.OpportunityTier;
import com.careeros.crawler.domain.NormalizedJob;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class OpportunityTierClassifier {
    private static final Set<String> PUBLIC_ORGANIZATIONS = Set.of(
            "government", "public_institution", "university", "hospital"
    );

    public OpportunityTierAssessment classify(NormalizedJob job) {
        String employment = job.position().employmentType();
        String organization = job.employer().organizationType();

        if ("劳务派遣".equals(employment)) {
            return result(job, OpportunityTier.EXCLUDED, employment, true,
                    "劳务派遣属于原始需求的默认排除范围");
        }
        if ("事业编制".equals(employment)) {
            boolean coherent = PUBLIC_ORGANIZATIONS.contains(organization);
            return result(job, coherent ? OpportunityTier.T1_ESTABLISHMENT_TARGET : OpportunityTier.T2_IDENTITY_REVIEW,
                    employment, coherent,
                    coherent ? "岗位用工身份明确为事业编制，进入 T1 主攻池"
                            : "岗位写明事业编制，但单位类型与之不一致，需要复核来源配置");
        }
        if ("state_owned_enterprise".equals(organization)) {
            return result(job, OpportunityTier.T3_STABLE_SOE_BACKUP, employment, true,
                    "单位类型为国有企业；可进入稳定国企备选池，但不得标成事业编");
        }
        if ("员额/报备员额".equals(employment)) {
            return result(job, OpportunityTier.T2_IDENTITY_REVIEW, employment, true,
                    "员额或报备员额不等同于事业编，单独进入 T2 核验池");
        }
        if ("合同制/编外".equals(employment)) {
            OpportunityTier tier = PUBLIC_ORGANIZATIONS.contains(organization)
                    ? OpportunityTier.T2_IDENTITY_REVIEW : OpportunityTier.EXCLUDED;
            return result(job, tier, employment, true,
                    tier == OpportunityTier.T2_IDENTITY_REVIEW
                            ? "公共机构合同制岗位，稳定性和身份需单独核验"
                            : "普通合同制/编外岗位不属于首期目标池");
        }
        if (PUBLIC_ORGANIZATIONS.contains(organization)) {
            return result(job, OpportunityTier.T2_IDENTITY_REVIEW, employment, false,
                    "单位具有公共属性，但公告未给出足够的用工身份结论");
        }
        return result(job, OpportunityTier.UNKNOWN, employment, false,
                "单位性质和用工身份不足以进入 T1/T2/T3 任一池");
    }

    private OpportunityTierAssessment result(
            NormalizedJob job, OpportunityTier tier, String identity, boolean sufficient, String reason
    ) {
        return new OpportunityTierAssessment(
                job.jobId(), tier, identity, sufficient, reason,
                new Evidence(job.source().announcementUrl(), job.source().evidenceText())
        );
    }
}
