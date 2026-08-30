package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class P0UniversityMigrationContractTest {
    private static final Pattern CONFIGURATION = Pattern.compile("(?s)\\s'(\\{.*?\\})'::jsonb");

    @Test
    void v56RegistersZjutTwoColumnAuditedHttpContract() throws Exception {
        var migration = migration("/db/migration/V56__onboard_zjut_official_recruitment.sql");
        var entries = migration.configuration().path("listingEntries");

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).path("entryUri").asText())
            .isEqualTo("http://www.rczp.zjut.edu.cn/5346/list.htm");
        assertThat(entries.get(1).path("entryUri").asText())
            .isEqualTo("http://www.rczp.zjut.edu.cn/5347/list.htm");
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.path("transportPolicy").asText()).isEqualTo("AUDITED_HTTP_READ_ONLY");
            assertThat(entry.path("completenessRequired").asBoolean()).isFalse();
            assertThat(entry.path("exactAuthorities").get(0).asText())
                .isEqualTo("www.rczp.zjut.edu.cn:80");
            assertThat(entry.path("reconcileReportedTotalByListingItems").asBoolean()).isTrue();
        });
        assertPartialAndIdempotent(migration.sql(), "ZJUT_RECRUITMENT");
    }

    @Test
    void v57RegistersHznuAsCurrentLifecycleEvidenceNotCompleteHistory() throws Exception {
        var migration = migration("/db/migration/V57__onboard_hznu_current_recruitment.sql");
        var entry = migration.configuration().path("listingEntries").get(0);

        assertThat(entry.path("entryUri").asText()).isEqualTo("https://rsc.hznu.edu.cn/rczpw/");
        assertThat(entry.path("mode").asText()).isEqualTo("LINKED_PAGE");
        assertThat(entry.path("role").asText()).isEqualTo("CAMPAIGN_STATE");
        assertThat(entry.path("completenessRequired").asBoolean()).isFalse();
        assertThat(entry.path("knownArchiveGapYears")).hasSize(3);
        assertPartialAndIdempotent(migration.sql(), "HZNU_RECRUITMENT");
    }

    private static Migration migration(String resource) throws Exception {
        try (var input = P0UniversityMigrationContractTest.class.getResourceAsStream(resource)) {
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
