package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ShangchengSourceMigrationContractTest {
    private static final Pattern CONFIGURATION = Pattern.compile(
        "(?s)\\s'(\\{.*?\\})'::jsonb");

    @Test
    void v50RegistersTheOfficialLifecycleColumnWithoutClaimingFullHistory() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V50__onboard_shangcheng_official_source.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var matcher = CONFIGURATION.matcher(sql);

            assertThat(matcher.find()).isTrue();
            var configuration = new ObjectMapper().readTree(matcher.group(1).replace("''", "'"));
            assertThat(configuration.path("listingEntries")).singleElement().satisfies(entry -> {
                assertThat(entry.path("code").asText()).isEqualTo("establishment-and-lifecycle");
                assertThat(entry.path("knownArchiveGapYears")).singleElement()
                    .satisfies(year -> assertThat(year.asInt()).isEqualTo(2025));
                assertThat(entry.path("completenessRequired").asBoolean()).isTrue();
            });
            assertThat(sql)
                .contains("HZ_SHANGCHENG_GOV", "connection_status = 'PARTIAL'",
                    "ON CONFLICT (source_id, recruitment_year) DO NOTHING",
                    "ON CONFLICT (source_id, checkpoint) DO NOTHING")
                .doesNotContain("connection_status = 'CONNECTED'");
        }
    }
}
