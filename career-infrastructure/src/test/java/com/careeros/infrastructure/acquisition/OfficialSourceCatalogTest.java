package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OfficialSourceCatalogTest {
    @Test
    void catalogRepresentsAllHangzhouDistrictScopesThroughThe2027WatchWindow() {
        var catalog = OfficialSourceCatalog.load();

        assertThat(catalog.sources()).extracting(OfficialSourceCatalog.SourceDefinition::code)
            .contains(
                "HZ_SHANGCHENG_GOV", "HZ_GONGSHU_GOV", "HZ_XIHU_GOV", "HZ_BINJIANG_GOV",
                "HZ_XIAOSHAN_GOV", "HZ_YUHANG_GOV", "HZ_LINPING_GOV", "HZ_QIANTANG_GOV",
                "HZ_FUYANG_GOV", "HZ_LINAN_GOV", "HZ_JIANDE_GOV", "HZ_TONGLU_GOV", "HZ_CHUNAN_GOV");
        assertThat(catalog.sources()).filteredOn(source -> source.code().startsWith("HZ_") && source.code().endsWith("_GOV"))
            .allMatch(source -> source.historicalYears().containsAll(java.util.List.of(2024, 2025, 2026, 2027)));
        var xihu = catalog.sources().stream().filter(source -> source.code().equals("HZ_XIHU_GOV"))
            .findFirst().orElseThrow();
        assertThat(xihu.enabled()).isTrue();
        assertThat(xihu.strategy()).isEqualTo(OfficialSourceCatalog.DiscoveryStrategy.JCMS_LISTING);
        assertThat(xihu.configuration())
            .containsEntry("historicalPaginationMode", "JCMS_PARAM_JSON")
            .containsEntry("adapterType", "JCMS_LISTING");
    }

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

    @Test
    void xihuJcmsContractDiscoversCurrentOfficialRecruitmentArticlesFromJsonHtml() {
        var source = OfficialSourceCatalog.load().sources().stream()
            .filter(value -> value.code().equals("HZ_XIHU_GOV")).findFirst().orElseThrow();
        byte[] response = """
            {"success":true,"data":{"html":"<div class='page-content'>
              <a href='/col/col1368377/art/2026/art_fda023d7591c4fbf820b53260da8962a.html'>
                西湖区人力资源和社会保障局公开招聘编外工作人员公告</a>
              <a href='/col/col1229349919/art/2025/art_a900cfc4dba7403887a8cc0a165aadf1.html'>
                2025年杭州市西湖区部分事业单位公开招聘工作人员公告</a>
            </div>"}}
            """.getBytes(StandardCharsets.UTF_8);

        assertThat(new StaticHtmlSourceDiscoverer().discover(source, response))
            .extracting(link -> link.uri().toString())
            .containsExactly(
                "https://www.hzxh.gov.cn/col/col1229349919/art/2025/art_a900cfc4dba7403887a8cc0a165aadf1.html",
                "https://www.hzxh.gov.cn/col/col1368377/art/2026/art_fda023d7591c4fbf820b53260da8962a.html");
    }
}
