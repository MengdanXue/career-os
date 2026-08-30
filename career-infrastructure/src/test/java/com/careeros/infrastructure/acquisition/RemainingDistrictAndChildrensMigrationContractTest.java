package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RemainingDistrictAndChildrensMigrationContractTest {
    private static final Pattern CONFIGURATION = Pattern.compile("(?s)\\s'(\\{.*?\\})'::jsonb");

    @Test
    void v60KeepsTongluUnconnectedWithoutFabricatingRecruitmentEvidence() throws Exception {
        try (var input = getClass().getResourceAsStream(
            "/db/migration/V60__audit_tonglu_unconnected_source.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql)
                .contains("HZ_TONGLU_GOV", "THEN 'NOT_CONNECTED'")
                .doesNotContain("INSERT INTO recruitment_source", "59028260");
        }
    }

    @Test
    void v61RegistersOnlyExplicitChunanOfficialEvidence() throws Exception {
        var chunan = migration("/db/migration/V61__onboard_chunan_fixed_official_evidence.sql");

        assertFixedEvidence(chunan, "HZ_CHUNAN_GOV", "2024", "qdh.gov.cn");
        assertThat(chunan.configuration().path("listingEntries").get(0).path("allowedHosts"))
            .hasSize(2);
    }

    @Test
    void v62RegistersChildrensHospitalCurrentCampaignWithoutHistoricalOverclaim() throws Exception {
        var migration = migration("/db/migration/V62__onboard_childrens_hospital_current_lifecycle.sql");
        var entry = migration.configuration().path("listingEntries").get(0);

        assertThat(entry.path("entryUri").asText())
            .isEqualTo("https://rs.hzch.org/apply/getMore.action?pageNumber=1");
        assertThat(entry.path("role").asText()).isEqualTo("CAMPAIGN_STATE");
        assertThat(entry.path("mode").asText()).isEqualTo("CAMPAIGN_STATE");
        assertThat(entry.path("completenessRequired").asBoolean()).isFalse();
        assertThat(entry.path("knownArchiveGapYears")).hasSize(2);
        assertPartialAndIdempotent(migration.sql(), "HZ_CHILDRENS_HOSPITAL");
    }

    private static void assertFixedEvidence(
        Migration migration, String sourceCode, String evidenceYear, String host
    ) {
        var entry = migration.configuration().path("listingEntries").get(0);
        assertThat(entry.path("mode").asText()).isEqualTo("FIXED_EVIDENCE");
        assertThat(entry.path("completenessRequired").asBoolean()).isFalse();
        assertThat(entry.path("knownArchiveGapYears")).hasSize(3);
        assertThat(entry.path("historicalEvidenceByYear").path(evidenceYear))
            .singleElement().satisfies(value -> assertThat(value.asText()).contains(host));
        assertPartialAndIdempotent(migration.sql(), sourceCode);
    }

    private static Migration migration(String resource) throws Exception {
        try (var input = RemainingDistrictAndChildrensMigrationContractTest.class
            .getResourceAsStream(resource)) {
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
