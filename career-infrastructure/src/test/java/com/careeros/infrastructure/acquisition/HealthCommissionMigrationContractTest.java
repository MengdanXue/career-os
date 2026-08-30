package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class HealthCommissionMigrationContractTest {
    private static final Pattern CONFIGURATION = Pattern.compile(
        "(?s)\\s'(\\{.*?\\})'::jsonb");

    @Test
    void v53RegistersBothRequiredHealthLifecycleColumnsWithoutClaimingCompletion() throws Exception {
        try (var input = getClass().getResourceAsStream(
            "/db/migration/V53__onboard_hangzhou_health_commission.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var matcher = CONFIGURATION.matcher(sql);
            assertThat(matcher.find()).isTrue();
            var configuration = new ObjectMapper().readTree(matcher.group(1).replace("''", "'"));

            assertThat(configuration.path("listingEntries")).hasSize(2);
            assertThat(configuration.path("listingEntries").get(0).path("entryUri").asText())
                .contains("col1229318903");
            assertThat(configuration.path("listingEntries").get(1).path("entryUri").asText())
                .contains("col1229318910");
            assertThat(configuration.path("listingEntries")).allSatisfy(entry -> {
                assertThat(entry.path("mode").asText()).isEqualTo("JCMS_PARAM_JSON");
                assertThat(entry.path("completenessRequired").asBoolean()).isTrue();
            });
            assertThat(sql)
                .contains("HZ_HEALTH_COMMISSION",
                    "WHEN target.connection_status = 'CONNECTED' THEN 'CONNECTED'",
                    "ELSE 'PARTIAL'",
                    "ON CONFLICT (source_id, recruitment_year) DO NOTHING",
                    "ON CONFLICT (source_id, checkpoint) DO NOTHING")
                .doesNotContain("SET recruitment_source_id = source.id,\n    connection_status = 'PARTIAL'");
        }
    }
}
