package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class P0DistrictMigrationContractTest {
    private static final Pattern CONFIGURATION = Pattern.compile(
        "(?s)\\s'(\\{.*?\\})'::jsonb");

    @Test
    void v54RegistersYuhangRowReconciledStructuredJcmsWithoutClaimingCompletion() throws Exception {
        var migration = migration("/db/migration/V54__onboard_yuhang_official_source.sql");
        var entry = migration.configuration().path("listingEntries").get(0);

        assertThat(entry.path("mode").asText()).isEqualTo("JCMS_PARAM_JSON");
        assertThat(entry.path("jcmsSearch").path("xxgkId").asText()).isEqualTo("W001-C001");
        assertThat(entry.path("reconcileReportedTotalByListingItems").asBoolean()).isTrue();
        assertPartialAndIdempotent(migration.sql(), "HZ_YUHANG_GOV");
    }

    @Test
    void v55RegistersXiaoshanConfiguredCountersAndThe2024ArchiveGap() throws Exception {
        var migration = migration("/db/migration/V55__onboard_xiaoshan_official_source.sql");
        var entry = migration.configuration().path("listingEntries").get(0);

        assertThat(entry.path("mode").asText()).isEqualTo("STATIC_SUFFIX_TEMPLATE");
        assertThat(entry.path("knownArchiveGapYears")).singleElement()
            .satisfies(year -> assertThat(year.asInt()).isEqualTo(2024));
        assertThat(entry.path("reportedTotalRegex").asText()).contains("count:");
        assertThat(entry.path("reconcileReportedTotalByListingItems").asBoolean()).isTrue();
        assertPartialAndIdempotent(migration.sql(), "HZ_XIAOSHAN_GOV");
    }

    private static Migration migration(String resource) throws Exception {
        try (var input = P0DistrictMigrationContractTest.class.getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var matcher = CONFIGURATION.matcher(sql);
            assertThat(matcher.find()).isTrue();
            JsonNode configuration = new ObjectMapper().readTree(matcher.group(1).replace("''", "'"));
            return new Migration(sql, configuration);
        }
    }

    private static void assertPartialAndIdempotent(String sql, String sourceCode) {
        assertThat(sql)
            .contains(sourceCode,
                "WHEN target.connection_status = 'CONNECTED' THEN 'CONNECTED'",
                "ELSE 'PARTIAL'",
                "ON CONFLICT (source_id, recruitment_year) DO NOTHING",
                "ON CONFLICT (source_id, checkpoint) DO NOTHING")
            .doesNotContain("SET recruitment_source_id = source.id,\n    connection_status = 'PARTIAL'");
    }

    private record Migration(String sql, JsonNode configuration) {}
}
