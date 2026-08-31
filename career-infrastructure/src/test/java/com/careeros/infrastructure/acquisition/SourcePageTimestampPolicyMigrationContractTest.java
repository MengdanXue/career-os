package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SourcePageTimestampPolicyMigrationContractTest {
    @Test
    void v74IgnoresOfficialPortalPageTimestampWithoutDiscardingRawArtifacts() throws Exception {
        try (var input = getClass().getResourceAsStream(
            "/db/migration/V74__ignore_official_portal_page_timestamp.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains(
                "ZJ_HRSS_INSTITUTION", "HZ_CHUNAN_GOV",
                "contentFingerprintIgnoreRegexes", "pageTimestamp");
        }
    }
}
