package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class GovernmentSoeJsonApiMigrationContractTest {
    private static final Pattern CONFIGURATION = Pattern.compile("(?s)\\s'(\\{.*?\\})'::jsonb");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void v64ConnectsCapitalJsonApiWithOfficialExcelHostAndHonestArchiveGaps() throws Exception {
        var configuration = configuration("/db/migration/V64__onboard_hangzhou_capital_group.sql");
        JsonNode entry = configuration.path("listingEntries").get(0);

        assertThat(entry.path("mode").asText()).isEqualTo("JSON_API");
        assertThat(entry.path("requestHeaders").path("language").asText()).isEqualTo("1");
        assertThat(entry.path("jsonUrlTemplate").asText()).isEqualTo("/newDet_{value}_8");
        assertThat(entry.path("jsonPublishedDateField").asText()).isEqualTo("issueTimeStr");
        assertThat(entry.path("knownArchiveGapYears")).extracting(JsonNode::asInt)
            .containsExactly(2024, 2025);
        assertThat(entry.path("allowedHosts")).anySatisfy(host ->
            assertThat(host.asText()).isEqualTo("hzzb-web.oss-cn-hangzhou.aliyuncs.com"));
        assertThat(configuration.path("listingAbsenceDeactivationEnabled").asBoolean()).isTrue();
    }

    @Test
    void v65ConnectsMetroPostApiWithImageEvidenceAndHonestArchiveGaps() throws Exception {
        var configuration = configuration("/db/migration/V65__onboard_hangzhou_metro_group.sql");
        JsonNode entry = configuration.path("listingEntries").get(0);

        assertThat(entry.path("mode").asText()).isEqualTo("JSON_API");
        assertThat(entry.path("httpMethod").asText()).isEqualTo("POST");
        assertThat(entry.path("paginationLocation").asText()).isEqualTo("BODY");
        assertThat(entry.path("requestBodyTemplate").asText())
            .contains("招聘", "{page}", "{pageSize}");
        assertThat(entry.path("jsonFetchUrlTemplate").asText())
            .isEqualTo("/api/section/articleInfo?articleId={value}");
        assertThat(entry.path("jsonFetchContentPath").asText()).isEqualTo("data.contentHtml");
        assertThat(entry.path("jsonPublishedDateField").asText()).isEqualTo("publishTime");
        assertThat(entry.path("knownArchiveGapYears")).extracting(JsonNode::asInt)
            .containsExactly(2024, 2025);
        assertThat(configuration.path("imageEvidenceSelector").asText()).isEqualTo("img[src]");
        assertThat(configuration.path("listingAbsenceDeactivationEnabled").asBoolean()).isTrue();
        assertThat(entry.path("allowedHosts")).anySatisfy(host ->
            assertThat(host.asText()).contains("hangzhou7.zos.ctyun.cn"));
    }

    private JsonNode configuration(String resource) throws Exception {
        try (var input = getClass().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var matcher = CONFIGURATION.matcher(sql);
            assertThat(matcher.find()).isTrue();
            assertThat(sql).contains("'PARTIAL'", "ON CONFLICT (source_id, recruitment_year) DO NOTHING",
                "ON CONFLICT (source_id, checkpoint) DO NOTHING");
            return JSON.readTree(matcher.group(1).replace("''", "'"));
        }
    }
}
