package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class UcasHangzhouSourceMigrationContractTest {
    private static final Pattern CONFIGURATION = Pattern.compile("(?s)\\s'(\\{.*?\\})'::jsonb");

    @Test
    void migrationRestrictsTheOfficialHttpArchiveAndKeepsCoveragePartial() throws Exception {
        String sql;
        try (var input = getClass().getResourceAsStream(
            "/db/migration/V68__onboard_ucas_hangzhou_recruitment.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var matcher = CONFIGURATION.matcher(sql);
        assertThat(matcher.find()).isTrue();
        var configuration = new ObjectMapper().readTree(matcher.group(1));

        assertThat(configuration.path("attachmentSelector").asText())
            .isEqualTo("a.__career_os_no_attachment__[href]");
        assertThat(sql).contains("'https://hias.ucas.ac.cn/'");
        assertThat(configuration.path("listingEntries")).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.path("mode").asText()).isEqualTo("LINKED_PAGE");
            assertThat(entry.path("transportPolicy").asText())
                .isEqualTo("AUDITED_HTTP_READ_ONLY");
            assertThat(entry.path("exactAuthorities").get(0).asText())
                .isEqualTo("hias.ucas.ac.cn:80");
            assertThat(entry.path("completenessRequired").asBoolean()).isFalse();
        });
        assertThat(sql).contains("'UCAS_HANGZHOU'", "'PARTIAL'");
    }
}
