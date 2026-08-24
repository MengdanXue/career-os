package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OfficialSourceCatalogTest {
    @Test
    void catalogContainsTwentyUniqueOfficialTargetsAcrossAllRoutes() {
        var catalog = OfficialSourceCatalog.load();

        assertThat(catalog.sources()).hasSizeGreaterThanOrEqualTo(20);
        assertThat(catalog.sources()).extracting(OfficialSourceCatalog.SourceDefinition::code)
            .doesNotHaveDuplicates();
        assertThat(catalog.sources()).extracting(OfficialSourceCatalog.SourceDefinition::routeCode)
            .contains("PUBLIC_TECH", "UNIVERSITY_HOSPITAL_IT", "RESEARCH_SUPPORT", "GOVERNMENT_SOE_DIGITAL");
        assertThat(catalog.sources()).allMatch(source ->
            URI.create(source.officialRootUrl()).getScheme().equals("https"));
        assertThat(catalog.sources()).allMatch(source -> source.historicalYears().containsAll(java.util.List.of(2024, 2025, 2026)));
        assertThat(catalog.sources()).filteredOn(OfficialSourceCatalog.SourceDefinition::enabled)
            .allMatch(source -> source.listingUrl() != null && !source.listingUrl().isBlank());
    }

    @Test
    void enabledStaticDefinitionDrivesSameHostDeterministicDiscovery() {
        var source = OfficialSourceCatalog.load().sources().stream()
            .filter(value -> value.code().equals("ZJGSU_RECRUITMENT")).findFirst().orElseThrow();
        byte[] html = """
            <a href="/2025/0414/c938a192675/page.htm">浙江工商大学信息化岗位公开招聘公告</a>
            <a href="https://example.com/job">外站招聘公告</a>
            """.getBytes(StandardCharsets.UTF_8);

        var links = new StaticHtmlSourceDiscoverer().discover(source, html);

        assertThat(links).singleElement().satisfies(link -> {
            assertThat(link.uri()).hasToString("https://talents.zjgsu.edu.cn/2025/0414/c938a192675/page.htm");
            assertThat(link.title()).contains("公开招聘");
        });
    }
}
