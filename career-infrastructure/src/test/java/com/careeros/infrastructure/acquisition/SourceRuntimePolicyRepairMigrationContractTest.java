package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SourceRuntimePolicyRepairMigrationContractTest {
    @Test
    void v72RepairsRateLimitRecognitionAndGongshuCounterReconciliation() throws Exception {
        try (var input = getClass().getResourceAsStream(
            "/db/migration/V72__repair_runtime_response_and_listing_reconciliation.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains(
                "HZ_HRSS_INSTITUTION", "ZJ_HRSS_INSTITUTION", "responseRejectRegexes",
                "访问过于频繁，请稍后再试", "HZ_GONGSHU_GOV",
                "reconcileReportedTotalByListingItems", "listingItemSelector", "itemLinkSelector");
        }
    }
}
