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
        assertThat(catalog.sources()).filteredOn(OfficialSourceCatalog.SourceDefinition::enabled)
            .extracting(OfficialSourceCatalog.SourceDefinition::code)
            .contains("HDU_RECRUITMENT", "ZJGSU_RECRUITMENT");
        var hdu = catalog.sources().stream().filter(source -> source.code().equals("HDU_RECRUITMENT"))
            .findFirst().orElseThrow();
        assertThat(hdu.listingUrl()).isEqualTo("https://renshi.hdu.edu.cn/13762/list.htm");
        assertThat(hdu.configuration())
            .containsEntry("historicalPaginationMode", "STATIC_PAGE_SUFFIX")
            .containsEntry("adapterType", "STATIC_HTML");
        var zjgsu = catalog.sources().stream().filter(source -> source.code().equals("ZJGSU_RECRUITMENT"))
            .findFirst().orElseThrow();
        assertThat(zjgsu.officialRootUrl()).isEqualTo("https://rsc.zjgsu.edu.cn/");
        assertThat(zjgsu.listingUrl()).isEqualTo("https://rsc.zjgsu.edu.cn/938/list.htm");
        var hospital = catalog.sources().stream().filter(source -> source.code().equals("HZ_FIRST_HOSPITAL"))
            .findFirst().orElseThrow();
        assertThat(hospital.enabled()).isTrue();
        assertThat(hospital.configuration().toString())
            .contains("FIXED_HTTPS_EVIDENCE", "HOSPITAL_OFFICIAL_EVIDENCE")
            .doesNotContain("http://zhaopin.hz-hospital.com:8080");
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
            assertThat(link.uri()).hasToString("https://rsc.zjgsu.edu.cn/2025/0414/c938a192675/page.htm");
            assertThat(link.title()).contains("公开招聘");
        });
    }
}
