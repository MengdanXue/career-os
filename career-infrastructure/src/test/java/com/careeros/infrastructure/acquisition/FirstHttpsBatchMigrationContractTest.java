package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FirstHttpsBatchMigrationContractTest {
    @Test
    void v45RegistersThreePartialSourcesWithAuditableMultiEntryContracts() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V45__onboard_first_https_multi_entry_sources.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql).contains("HZ_TCM_HOSPITAL", "HZ_XIXI_HOSPITAL", "HZ_DATA_GROUP");
            assertThat(sql).contains("\"listingEntries\"", "\"imageEvidenceSelector\"");
            assertThat(sql).contains("STATIC_SUFFIX_TEMPLATE", "QUERY_PAGE");
            assertThat(sql).contains("connection_status = 'PARTIAL'");
            assertThat(sql).contains(
                "'REGISTERED'", "'CONTRACT_VERIFIED'", "'LIVE_SMOKE_VERIFIED'",
                "'BACKFILL_COMPLETE'", "'INCREMENTAL_VERIFIED'");
            assertThat(sql).doesNotContain("connection_status = 'CONNECTED'");
        }
    }
}
