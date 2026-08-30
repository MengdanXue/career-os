package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class TongluSourceMigrationContractTest {
    private static final Pattern CONFIGURATION = Pattern.compile("(?s)\\s'(\\{.*?\\})'::jsonb");

    @Test
    void migrationUsesOfficialSearchHtmlFragmentsAndKeepsArchivePartial() throws Exception {
        String sql;
        try (var input = getClass().getResourceAsStream(
            "/db/migration/V69__onboard_tonglu_official_search.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var matcher = CONFIGURATION.matcher(sql);
        assertThat(matcher.find()).isTrue();
        var configuration = new ObjectMapper().readTree(matcher.group(1));
        var entry = configuration.path("listingEntries").get(0);

        assertThat(entry.path("mode").asText()).isEqualTo("JSON_HTML_FRAGMENTS");
        assertThat(entry.path("httpMethod").asText()).isEqualTo("POST");
        assertThat(entry.path("fragmentRedirectQueryParameter").asText()).isEqualTo("url");
        assertThat(entry.path("fragmentUpgradeHttpHosts").get(0).asText())
            .isEqualTo("www.tonglu.gov.cn");
        assertThat(entry.path("knownArchiveGapYears")).hasSize(2);
        assertThat(entry.path("completenessRequired").asBoolean()).isFalse();
        assertThat(sql).contains("'HZ_TONGLU_GOV'", "'PARTIAL'");
    }
}
