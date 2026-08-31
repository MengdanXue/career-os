package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SourceOperationalRepairMigrationContractTest {
    @Test
    void v71RepairsLegacyIncrementalAndDynamicResponsePolicies() throws Exception {
        try (var input = getClass().getResourceAsStream(
            "/db/migration/V71__repair_source_incremental_and_fingerprint_policies.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql)
                .contains("HDU_RECRUITMENT", "ZJGSU_RECRUITMENT", "HZ_GONGSHU_GOV",
                    "HZ_HRSS_INSTITUTION", "ZJ_HRSS_INSTITUTION", "HZ_XIHU_GOV",
                    "incrementalListingMaxPages", "contentFingerprintIgnoreRegexes",
                    "responseRejectRegexes", "HZ_FIRST_HOSPITAL", "HZ_TCM_HOSPITAL",
                    "HZ_XIXI_HOSPITAL", "HZ_TONGLU_GOV")
                .doesNotContain("UPDATE recruitment_source SET configuration = '{}'::jsonb");
        }
    }
}
