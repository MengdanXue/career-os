package com.careeros.domain.acquisition;

import static com.careeros.domain.acquisition.TargetSource.AuthorityLevel.OFFICIAL_ORGANIZATION;
import static com.careeros.domain.acquisition.TargetSource.ConnectionStatus.CONNECTED;
import static com.careeros.domain.acquisition.TargetSource.ConnectionStatus.NOT_CONNECTED;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TargetSourceTest {
    @Test
    void notConnectedTargetSourcesPreventMarketCompleteConclusion() {
        var target = new TargetSource("HZ_DATA_GROUP", "杭州数据集团", "GOVERNMENT_SOE_DIGITAL",
            "杭州", OFFICIAL_ORGANIZATION, NOT_CONNECTED, "https://hr.hzfi.cn/");

        assertThat(target.supportsAbsenceConclusion()).isFalse();
    }

    @Test
    void connectedOfficialTargetCanParticipateInCoverageAssessment() {
        var target = new TargetSource("HZ_HRSS_INSTITUTION", "杭州市人社局", "PUBLIC_TECH",
            "杭州", OFFICIAL_ORGANIZATION, CONNECTED, "https://hrss.hangzhou.gov.cn/");

        assertThat(target.supportsAbsenceConclusion()).isTrue();
    }
}
