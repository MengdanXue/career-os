package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class HangzhouMedicineInstituteMigrationContractTest {
    private static final Pattern CONFIGURATION = Pattern.compile("(?s)\\s'(\\{.*?\\})'::jsonb");

    @Test
    void v63RegistersTargetAndOfficialEngineeringRecruitmentWithScriptAttachments() throws Exception {
        try (var input = getClass().getResourceAsStream(
            "/db/migration/V63__onboard_hangzhou_medicine_institute.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var matcher = CONFIGURATION.matcher(sql);
            assertThat(matcher.find()).isTrue();
            var configuration = new ObjectMapper().readTree(matcher.group(1).replace("''", "'"));
            var entry = configuration.path("listingEntries").get(0);

            assertThat(configuration.path("scriptAttachmentVariable").asText())
                .isEqualTo("appLinkStr");
            assertThat(entry.path("mode").asText()).isEqualTo("LINKED_PAGE");
            assertThat(entry.path("knownArchiveGapYears")).singleElement()
                .satisfies(year -> assertThat(year.asInt()).isEqualTo(2024));
            assertThat(entry.path("completenessRequired").asBoolean()).isTrue();
            assertThat(sql)
                .contains("CAS_HANGZHOU_MEDICINE", "RESEARCH_INSTITUTE", "RESEARCH_SUPPORT",
                    "WHEN target.connection_status = 'CONNECTED' THEN 'CONNECTED'",
                    "ELSE 'PARTIAL'",
                    "ON CONFLICT (source_id, recruitment_year) DO NOTHING",
                    "ON CONFLICT (source_id, checkpoint) DO NOTHING")
                .doesNotContain("SET recruitment_source_id = source.id,\n    connection_status = 'PARTIAL'");
        }
    }
}
