package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class WestlakeSourceMigrationContractTest {
    private static final Pattern CONFIGURATION = Pattern.compile("(?s)\\s'(\\{.*?\\})'::jsonb");

    @Test
    void migrationUsesTheOfficialInlineJavascriptDatasetAndKeepsCoveragePartial() throws Exception {
        String sql;
        try (var input = getClass().getResourceAsStream(
            "/db/migration/V67__onboard_westlake_engineering_recruitment.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var matcher = CONFIGURATION.matcher(sql);
        assertThat(matcher.find()).isTrue();
        var configuration = new ObjectMapper().readTree(matcher.group(1));
        var entry = configuration.path("listingEntries").get(0);

        assertThat(entry.path("mode").asText()).isEqualTo("JS_OBJECT_ARRAY");
        assertThat(entry.path("embeddedArrayMarker").asText()).isEqualTo("talentList");
        assertThat(entry.path("jsonUrlField").asText()).isEqualTo("url");
        assertThat(entry.path("jsonPublishedDateField").asText()).isEqualTo("date");
        assertThat(entry.path("completenessRequired").asBoolean()).isFalse();
        assertThat(entry.path("titleExcludeRegex").asText())
            .contains("博士后", "教授", "教师", "行政助理");
        assertThat(sql).contains("'WESTLAKE_RESEARCH'", "'PARTIAL'");
    }
}
