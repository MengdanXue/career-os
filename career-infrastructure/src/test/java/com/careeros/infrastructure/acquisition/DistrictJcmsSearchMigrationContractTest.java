package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DistrictJcmsSearchMigrationContractTest {
    private static final Pattern CONFIGURATION = Pattern.compile(
        "(?s)\\s'(\\{.*?\\})'::jsonb");

    @Test
    void v51RegistersLinanStructuredSearchWithoutClaimingDistrictCompletion() throws Exception {
        assertMigration(
            "/db/migration/V51__onboard_linan_official_source.sql",
            "HZ_LINAN_GOV", "F001", "人事信息", null);
    }

    @Test
    void v52RegistersJiandeStructuredSearchAndIts2024ArchiveGap() throws Exception {
        assertMigration(
            "/db/migration/V52__onboard_jiande_official_source.sql",
            "HZ_JIANDE_GOV", "JD16-JD1602", "招聘招录", 2024);
    }

    private void assertMigration(
        String resource, String sourceCode, String xxgkId, String className, Integer gapYear
    ) throws Exception {
        try (var input = getClass().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var matcher = CONFIGURATION.matcher(sql);
            assertThat(matcher.find()).isTrue();
            var configuration = new ObjectMapper().readTree(matcher.group(1).replace("''", "'"));
            var entry = configuration.path("listingEntries").get(0);

            assertThat(entry.path("mode").asText()).isEqualTo("JCMS_PARAM_JSON");
            assertThat(entry.path("jcmsSearch").path("xxgkId").asText()).isEqualTo(xxgkId);
            assertThat(entry.path("jcmsSearch").path("className").asText()).isEqualTo(className);
            assertThat(entry.path("completenessRequired").asBoolean()).isTrue();
            if (gapYear == null) assertThat(entry.has("knownArchiveGapYears")).isFalse();
            else assertThat(entry.path("knownArchiveGapYears")).singleElement()
                .satisfies(year -> assertThat(year.asInt()).isEqualTo(gapYear));
            assertThat(sql)
                .contains(sourceCode, "connection_status = 'PARTIAL'",
                    "ON CONFLICT (source_id, recruitment_year) DO NOTHING",
                    "ON CONFLICT (source_id, checkpoint) DO NOTHING")
                .doesNotContain("connection_status = 'CONNECTED'");
        }
    }
}
