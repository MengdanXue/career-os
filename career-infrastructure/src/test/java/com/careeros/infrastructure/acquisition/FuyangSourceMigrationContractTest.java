package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class FuyangSourceMigrationContractTest {
    private static final Pattern CONFIGURATION = Pattern.compile(
        "(?s)\\s'(\\{.*?\\})'::jsonb");

    @Test
    void v49RegistersBothVerifiedOfficialSubtreesWithoutClaimingCompleteCoverage() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V49__onboard_fuyang_official_sources.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var matcher = CONFIGURATION.matcher(sql);

            assertThat(matcher.find()).isTrue();
            var configuration = new ObjectMapper().readTree(matcher.group(1).replace("''", "'"));
            assertThat(configuration.path("listingEntries")).hasSize(2);
            assertThat(configuration.path("listingEntries").get(0).path("code").asText())
                .isEqualTo("establishment");
            assertThat(configuration.path("listingEntries").get(1).path("code").asText())
                .isEqualTo("health-establishment");
            assertThat(configuration.path("listingEntries")).allSatisfy(entry ->
                assertThat(entry.path("completenessRequired").asBoolean()).isTrue());
            assertThat(sql)
                .contains("HZ_FUYANG_GOV", "connection_status = 'PARTIAL'",
                    "2024", "官方档案缺口", "'NOT_DISCOVERED'",
                    "ON CONFLICT (source_id, recruitment_year) DO NOTHING",
                    "ON CONFLICT (source_id, checkpoint) DO NOTHING")
                .doesNotContain("connection_status = 'CONNECTED'",
                    "status = 'NOT_DISCOVERED'", "discovered_count = 0");
        }
    }
}
