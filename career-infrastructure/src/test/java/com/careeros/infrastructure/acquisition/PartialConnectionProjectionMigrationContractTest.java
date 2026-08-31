package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PartialConnectionProjectionMigrationContractTest {
    @Test
    void v77RepairsOnlyMeaningfulPartialRunsAndKeepsFullFailuresVisible() throws Exception {
        try (var input = getClass().getResourceAsStream(
            "/db/migration/V77__repair_partial_run_connection_projection.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains(
                "latest.status = 'PARTIALLY_SUCCEEDED'",
                "latest.successful_items > 0",
                "target.connection_status = 'FAILED'",
                "connection_status = 'PARTIAL'");
            assertThat(sql).doesNotContain("latest.status = 'FAILED'");
        }
    }
}
