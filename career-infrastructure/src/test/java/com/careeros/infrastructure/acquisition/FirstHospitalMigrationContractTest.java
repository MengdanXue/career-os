package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class FirstHospitalMigrationContractTest {
    private static final Pattern CONFIGURATION = Pattern.compile(
        "(?s)\\s'(\\{.*?\\})'::jsonb");

    @Test
    void v47ReplacesDeadEvidenceWithExactReadOnlyAuthorityAndKeepsSourcePartial() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V47__connect_first_hospital_exact_http.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var matcher = CONFIGURATION.matcher(sql);

            assertThat(matcher.find()).isTrue();
            var configuration = new ObjectMapper().readTree(matcher.group(1).replace("''", "'"));
            var entry = configuration.path("listingEntries").get(0);
            assertThat(configuration.path("historicalYears").toString())
                .isEqualTo("[2024,2025,2026,2027]");
            assertThat(entry.path("entryUri").asText())
                .isEqualTo("http://124.160.72.42:8080/apply/getMore.action?pageNumber=1");
            assertThat(entry.path("exactAuthorities").get(0).asText())
                .isEqualTo("124.160.72.42:8080");
            assertThat(entry.path("recruitmentYears").toString())
                .isEqualTo("[2025,2026,2027]");
            assertThat(configuration.path("imageEvidenceSelector").asText())
                .isEqualTo("img[src*='file_zp/attached/']");
            assertThat(sql)
                .contains("connection_status = 'PARTIAL'", "2024 无官方归档",
                    "ON CONFLICT (source_id, recruitment_year) DO UPDATE SET",
                    "status = 'NOT_DISCOVERED'", "completion_basis = NULL",
                    "listing_page_count = 0")
                .doesNotContain("connection_status = 'CONNECTED'", "zp.hz-hospital.com",
                    "renshi.wechathospital.com");
        }
    }
}
