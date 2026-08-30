package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OfficialSourceCatalogTest {
    @Test
    void enabledMultiEntryDefinitionProjectsDeeplyImmutableListingContracts() {
        var definition = new OfficialSourceCatalog.SourceDefinition(
            "HZ_TCM_HOSPITAL", "杭州市中医院招聘", "UNIVERSITY_HOSPITAL_IT",
            "https://www.hztcm.net/", null,
            OfficialSourceCatalog.DiscoveryStrategy.STATIC_HTML,
            List.of(2024, 2025, 2026), true, null, null, null, null, null, null,
            List.of(Map.of(
                "code", "recruitment",
                "entryUri", "https://hztcm.unitedsoft.cn/News130a1.htm",
                "role", "PRIMARY",
                "mode", "STATIC_SUFFIX_TEMPLATE",
                "recruitmentYears", List.of(2024, 2025, 2026),
                "articleUrlRegex", "^https://hztcm\\.unitedsoft\\.cn/View\\d+a130\\.htm$")));

        assertThat(definition.configuration()).containsKey("listingEntries");
        assertThat(definition.listingEntries()).hasSize(1);
        assertThat(definition.listingUrl()).isNull();
        assertThatThrownBy(() -> definition.listingEntries().getFirst().put("mode", "FIXED_EVIDENCE"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

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
        var gongshu = catalog.sources().stream().filter(source -> source.code().equals("HZ_GONGSHU_GOV"))
            .findFirst().orElseThrow();
        assertThat(gongshu.enabled()).isTrue();
        assertThat(gongshu.listingUrl())
            .isEqualTo("https://www.gongshu.gov.cn/col/col1229226160/index.html");
        assertThat(gongshu.strategy()).isEqualTo(OfficialSourceCatalog.DiscoveryStrategy.JCMS_LISTING);
        assertThat(gongshu.configuration())
            .containsEntry("historicalPaginationMode", "JCMS_PARAM_JSON")
            .containsEntry("adapterType", "JCMS_LISTING");
        var qiantang = catalog.sources().stream().filter(source -> source.code().equals("HZ_QIANTANG_GOV"))
            .findFirst().orElseThrow();
        assertThat(qiantang.enabled()).isTrue();
        assertThat(qiantang.listingUrl())
            .isEqualTo("https://www.qiantang.gov.cn/col/col1657687/index.html");
        assertThat(qiantang.strategy()).isEqualTo(OfficialSourceCatalog.DiscoveryStrategy.JCMS_LISTING);
        assertThat(qiantang.configuration())
            .containsEntry("historicalPaginationMode", "JCMS_PARAM_JSON")
            .containsEntry("adapterType", "JCMS_LISTING");
    }

    @Test
    void catalogPublishesVerifiedMultiEntryContractsForFirstHttpsBatch() {
        var catalog = OfficialSourceCatalog.load();

        var tcm = catalog.sources().stream()
            .filter(source -> source.code().equals("HZ_TCM_HOSPITAL")).findFirst().orElseThrow();
        var xixi = catalog.sources().stream()
            .filter(source -> source.code().equals("HZ_XIXI_HOSPITAL")).findFirst().orElseThrow();
        var data = catalog.sources().stream()
            .filter(source -> source.code().equals("HZ_DATA_GROUP")).findFirst().orElseThrow();

        assertThat(tcm.enabled()).isTrue();
        assertThat(tcm.listingEntries()).extracting(entry -> entry.get("code"))
            .containsExactly("recruitment", "exam-lifecycle");
        assertThat(xixi.enabled()).isTrue();
        assertThat(xixi.listingEntries()).extracting(entry -> entry.get("code"))
            .containsExactly("announcements", "exam-lifecycle");
        assertThat(xixi.configuration())
            .containsEntry("imageEvidenceSelector", ".content img[src*='/ueditor/php/upload/image/']");
        assertThat(data.enabled()).isTrue();
        assertThat(data.listingEntries()).singleElement().satisfies(entry -> {
            assertThat(entry).containsEntry("itemUriAttribute", "data-id");
            assertThat(entry).containsEntry("reportedTotalRegex", "var\\s+recordNum\\s*=\\s*(\\d+)");
        });
        assertThat(data.listingUrl()).isNull();
    }

    @Test
    void catalogPublishesChildrensHospitalCurrentLifecycleAndDistrictFixedEvidence() {
        var catalog = OfficialSourceCatalog.load();

        var children = catalog.sources().stream()
            .filter(source -> source.code().equals("HZ_CHILDRENS_HOSPITAL"))
            .findFirst().orElseThrow();
        assertThat(children.enabled()).isTrue();
        assertThat(children.strategy()).isEqualTo(OfficialSourceCatalog.DiscoveryStrategy.STATIC_HTML);
        assertThat(children.listingEntries()).singleElement().satisfies(entry -> {
            assertThat(entry)
                .containsEntry("entryUri", "https://rs.hzch.org/apply/getMore.action?pageNumber=1")
                .containsEntry("role", "CAMPAIGN_STATE")
                .containsEntry("mode", "CAMPAIGN_STATE")
                .containsEntry("knownArchiveGapYears", List.of(2024, 2025))
                .containsEntry("completenessRequired", false);
            assertThat(entry.get("articleUrlRegex").toString()).contains("getNotice\\.action");
        });

        var chunan = catalog.sources().stream()
            .filter(source -> source.code().equals("HZ_CHUNAN_GOV")).findFirst().orElseThrow();
        assertThat(chunan.enabled()).isTrue();
        assertThat(chunan.listingEntries()).singleElement().satisfies(entry -> {
            assertThat(entry)
                .containsEntry("role", "HISTORICAL")
                .containsEntry("mode", "FIXED_EVIDENCE")
                .containsEntry("knownArchiveGapYears", List.of(2024, 2025, 2026))
                .containsEntry("completenessRequired", false);
            assertThat(entry.get("historicalEvidenceByYear")).isInstanceOf(Map.class);
            assertThat(entry.get("allowedHosts").toString()).contains("qdh.gov.cn", "zjjcmspublic");
        });

        var tonglu = catalog.sources().stream()
            .filter(source -> source.code().equals("HZ_TONGLU_GOV")).findFirst().orElseThrow();
        assertThat(tonglu.enabled()).isFalse();
        assertThat(tonglu.strategy()).isEqualTo(OfficialSourceCatalog.DiscoveryStrategy.UNSUPPORTED);
        assertThat(tonglu.listingEntries()).isEmpty();
    }

    @Test
    void catalogPublishesHangzhouInstituteOfMedicineEngineeringRecruitment() {
        var source = OfficialSourceCatalog.load().sources().stream()
            .filter(candidate -> candidate.code().equals("CAS_HANGZHOU_MEDICINE"))
            .findFirst().orElseThrow();

        assertThat(source.enabled()).isTrue();
        assertThat(source.strategy()).isEqualTo(OfficialSourceCatalog.DiscoveryStrategy.STATIC_HTML);
        assertThat(source.configuration())
            .containsEntry("scriptAttachmentVariable", "appLinkStr");
        assertThat(source.listingEntries()).singleElement().satisfies(entry -> {
            assertThat(entry)
                .containsEntry("entryUri", "https://him.cas.cn/rczp/zpgg/")
                .containsEntry("role", "PRIMARY")
                .containsEntry("mode", "LINKED_PAGE")
                .containsEntry("knownArchiveGapYears", List.of(2024))
                .containsEntry("completenessRequired", true)
                .containsEntry("historicalMaxPages", 1);
            assertThat(entry.get("titleExcludeRegex").toString())
                .contains("科研助理招聘启事", "博士后招聘公告", "劳务派遣");
        });
    }

    @Test
    void catalogPublishesCapitalAndMetroOfficialJsonRecruitmentContracts() {
        var catalog = OfficialSourceCatalog.load();
        var capital = catalog.sources().stream()
            .filter(source -> source.code().equals("HZ_CAPITAL_GROUP"))
            .findFirst().orElseThrow();
        var metro = catalog.sources().stream()
            .filter(source -> source.code().equals("HZ_METRO_GROUP"))
            .findFirst().orElseThrow();

        assertThat(capital.enabled()).isTrue();
        assertThat(capital.officialRootUrl()).isEqualTo("https://www.hzzbco.com/");
        assertThat(capital.configuration()).containsEntry("attachmentSelector", "a[href]");
        assertThat(capital.configuration()).containsEntry("listingAbsenceDeactivationEnabled", true);
        assertThat(capital.allowedHosts()).contains("hzzb-web.oss-cn-hangzhou.aliyuncs.com");
        assertThat(capital.listingEntries()).singleElement().satisfies(entry -> {
            assertThat(entry)
                .containsEntry("mode", "JSON_API")
                .containsEntry("jsonUrlTemplate", "/newDet_{value}_8")
                .containsEntry("jsonPublishedDateField", "issueTimeStr")
                .containsEntry("knownArchiveGapYears", List.of(2024, 2025));
            assertThat(entry.get("allowedHosts").toString()).contains("hzzb-web.oss-cn-hangzhou.aliyuncs.com");
        });

        assertThat(metro.enabled()).isTrue();
        assertThat(metro.configuration()).containsEntry("imageEvidenceSelector", "img[src]");
        assertThat(metro.configuration()).containsEntry("listingAbsenceDeactivationEnabled", true);
        assertThat(metro.allowedHosts()).contains("prod-bucket-mh.hangzhou7.zos.ctyun.cn");
        assertThat(metro.listingEntries()).singleElement().satisfies(entry -> {
            assertThat(entry)
                .containsEntry("mode", "JSON_API")
                .containsEntry("httpMethod", "POST")
                .containsEntry("paginationLocation", "BODY")
                .containsEntry("jsonFetchUrlTemplate", "/api/section/articleInfo?articleId={value}")
                .containsEntry("jsonFetchContentPath", "data.contentHtml")
                .containsEntry("jsonPublishedDateField", "publishTime")
                .containsEntry("knownArchiveGapYears", List.of(2024, 2025));
            assertThat(entry.get("requestBodyTemplate").toString())
                .contains("招聘", "{page}", "{pageSize}");
        });
    }

    @Test
    void catalogPublishesFuyangGeneralAndHealthJcmsContractsAsPartialCoverageInputs() {
        var fuyang = OfficialSourceCatalog.load().sources().stream()
            .filter(source -> source.code().equals("HZ_FUYANG_GOV"))
            .findFirst().orElseThrow();

        assertThat(fuyang.enabled()).isTrue();
        assertThat(fuyang.strategy()).isEqualTo(OfficialSourceCatalog.DiscoveryStrategy.JCMS_LISTING);
        assertThat(fuyang.listingEntries()).extracting(entry -> entry.get("code"))
            .containsExactly("establishment", "health-establishment");
        assertThat(fuyang.listingEntries()).allSatisfy(entry -> {
            assertThat(entry).containsEntry("mode", "STATIC_SUFFIX_TEMPLATE");
            assertThat(entry).containsEntry("completenessRequired", true);
            assertThat(entry.get("pageUriTemplate").toString())
                .contains("pageNo%22%3A{page}", "search%22%3A%22");
            assertThat(entry).containsEntry("reportedTotalRegex", "count=\\\\\"(\\d+)\\\\\"");
            assertThat(entry).containsEntry("reportedCurrentPageRegex", "pageNo=\\\\\"(\\d+)\\\\\"");
        });
    }

    @Test
    void catalogPublishesShangchengRecruitmentLifecycleWithTheVerified2025Gap() {
        var shangcheng = OfficialSourceCatalog.load().sources().stream()
            .filter(source -> source.code().equals("HZ_SHANGCHENG_GOV"))
            .findFirst().orElseThrow();

        assertThat(shangcheng.enabled()).isTrue();
        assertThat(shangcheng.strategy())
            .isEqualTo(OfficialSourceCatalog.DiscoveryStrategy.JCMS_LISTING);
        assertThat(shangcheng.historicalYears()).containsExactly(2024, 2025, 2026, 2027);
        assertThat(shangcheng.listingEntries()).singleElement().satisfies(entry -> {
            assertThat(entry)
                .containsEntry("code", "establishment-and-lifecycle")
                .containsEntry("role", "PRIMARY")
                .containsEntry("mode", "STATIC_SUFFIX_TEMPLATE")
                .containsEntry("knownArchiveGapYears", List.of(2025))
                .containsEntry("completenessRequired", true)
                .containsEntry("historicalMaxPages", 10)
                .containsEntry("incrementalListingMaxPages", 2)
                .containsEntry("reportedTotalRegex", "count:\\s*\\\\\"(\\d+)\\\\\"")
                .containsEntry("reportedCurrentPageRegex", "pageNo:\\s*\\\\\"(\\d+)\\\\\"");
            assertThat(entry.get("pageUriTemplate").toString())
                .contains("pageId=1229554150", "pageNo%22%3A{page}");
        });
    }

    @Test
    void catalogPublishesLinanAndJiandeAsStructuredJcmsSearchSources() {
        var catalog = OfficialSourceCatalog.load();
        var linan = catalog.sources().stream()
            .filter(source -> source.code().equals("HZ_LINAN_GOV"))
            .findFirst().orElseThrow();
        var jiande = catalog.sources().stream()
            .filter(source -> source.code().equals("HZ_JIANDE_GOV"))
            .findFirst().orElseThrow();

        assertThat(linan.enabled()).isTrue();
        assertThat(linan.strategy()).isEqualTo(OfficialSourceCatalog.DiscoveryStrategy.JCMS_LISTING);
        assertThat(linan.listingEntries()).singleElement().satisfies(entry -> {
            assertThat(entry)
                .containsEntry("mode", "JCMS_PARAM_JSON")
                .containsEntry("historicalPageSize", 15)
                .containsEntry("historicalMaxPages", 12)
                .containsEntry("completenessRequired", true);
            assertThat(entry.get("jcmsSearch")).isEqualTo(Map.of(
                "xxgkId", "F001", "xxgkType", "", "className", "人事信息"));
        });

        assertThat(jiande.enabled()).isTrue();
        assertThat(jiande.strategy()).isEqualTo(OfficialSourceCatalog.DiscoveryStrategy.JCMS_LISTING);
        assertThat(jiande.listingEntries()).singleElement().satisfies(entry -> {
            assertThat(entry)
                .containsEntry("mode", "JCMS_PARAM_JSON")
                .containsEntry("historicalPageSize", 15)
                .containsEntry("historicalMaxPages", 15)
                .containsEntry("knownArchiveGapYears", List.of(2024))
                .containsEntry("completenessRequired", true);
            assertThat(entry.get("entryUri").toString())
                .contains("col1229535302", "number=JD16-JD1602");
            assertThat(entry.get("jcmsSearch")).isEqualTo(Map.of(
                "xxgkId", "JD16-JD1602", "xxgkType", "", "className", "招聘招录"));
        });
    }

    @Test
    void catalogPublishesHealthCommissionAnnouncementsAndPublicityAsRequiredLifecycleEntries() {
        var health = OfficialSourceCatalog.load().sources().stream()
            .filter(source -> source.code().equals("HZ_HEALTH_COMMISSION"))
            .findFirst().orElseThrow();

        assertThat(health.enabled()).isTrue();
        assertThat(health.strategy()).isEqualTo(OfficialSourceCatalog.DiscoveryStrategy.JCMS_LISTING);
        assertThat(health.historicalYears()).containsExactly(2024, 2025, 2026, 2027);
        assertThat(health.listingEntries()).extracting(entry -> entry.get("code"))
            .containsExactly("recruitment-announcements", "appointment-publicity");
        assertThat(health.listingEntries()).allSatisfy(entry -> {
            assertThat(entry)
                .containsEntry("mode", "JCMS_PARAM_JSON")
                .containsEntry("historicalPageSize", 15)
                .containsEntry("reconcileReportedTotalByListingItems", true)
                .containsEntry("completenessRequired", true);
            assertThat(entry.get("allowedHosts")).isEqualTo(List.of(
                "wsjkw.hangzhou.gov.cn",
                "zjjcmspublicnew.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn"));
        });
        assertThat(health.listingEntries().get(0))
            .containsEntry("historicalMaxPages", 16)
            .containsEntry("role", "PRIMARY");
        assertThat(health.listingEntries().get(1))
            .containsEntry("historicalMaxPages", 22)
            .containsEntry("role", "LIFECYCLE");
    }

    @Test
    void catalogPublishesYuhangAndXiaoshanVerifiedP0DistrictContracts() {
        var catalog = OfficialSourceCatalog.load();
        var yuhang = catalog.sources().stream()
            .filter(source -> source.code().equals("HZ_YUHANG_GOV")).findFirst().orElseThrow();
        var xiaoshan = catalog.sources().stream()
            .filter(source -> source.code().equals("HZ_XIAOSHAN_GOV")).findFirst().orElseThrow();

        assertThat(yuhang.enabled()).isTrue();
        assertThat(yuhang.strategy()).isEqualTo(OfficialSourceCatalog.DiscoveryStrategy.JCMS_LISTING);
        assertThat(yuhang.listingEntries()).singleElement().satisfies(entry -> {
            assertThat(entry)
                .containsEntry("mode", "JCMS_PARAM_JSON")
                .containsEntry("historicalPageSize", 15)
                .containsEntry("historicalMaxPages", 20)
                .containsEntry("reconcileReportedTotalByListingItems", true)
                .containsEntry("completenessRequired", true);
            assertThat(entry.get("entryUri")).isEqualTo(
                "https://www.yuhang.gov.cn/col/col1229191870/index.html");
            assertThat(entry.get("jcmsSearch")).isEqualTo(Map.of(
                "xxgkId", "W001-C001", "xxgkType", "", "className", "人员考录"));
        });

        assertThat(xiaoshan.enabled()).isTrue();
        assertThat(xiaoshan.strategy()).isEqualTo(OfficialSourceCatalog.DiscoveryStrategy.JCMS_LISTING);
        assertThat(xiaoshan.listingEntries()).singleElement().satisfies(entry -> {
            assertThat(entry)
                .containsEntry("mode", "STATIC_SUFFIX_TEMPLATE")
                .containsEntry("historicalMaxPages", 5)
                .containsEntry("knownArchiveGapYears", List.of(2024))
                .containsEntry("reconcileReportedTotalByListingItems", true)
                .containsEntry("completenessRequired", true);
            assertThat(entry.get("pageUriTemplate").toString())
                .contains("pageId=1229292680", "pageNo%22%3A{page}", "pageSize%22%3A20");
        });
    }

    @Test
    void catalogPublishesBinjiangAndLinpingAsTruthfulPartialSectorCoverage() {
        var catalog = OfficialSourceCatalog.load();
        var binjiang = catalog.sources().stream()
            .filter(source -> source.code().equals("HZ_BINJIANG_GOV")).findFirst().orElseThrow();
        var linping = catalog.sources().stream()
            .filter(source -> source.code().equals("HZ_LINPING_GOV")).findFirst().orElseThrow();

        assertThat(binjiang.enabled()).isTrue();
        assertThat(binjiang.listingEntries()).singleElement().satisfies(entry -> assertThat(entry)
            .containsEntry("mode", "STATIC_SUFFIX_TEMPLATE")
            .containsEntry("historicalPageSize", 50)
            .containsEntry("historicalMaxPages", 25)
            .containsEntry("knownArchiveGapYears", List.of(2024, 2025, 2026))
            .containsEntry("reconcileReportedTotalByListingItems", true));

        assertThat(linping.enabled()).isTrue();
        assertThat(linping.listingEntries()).singleElement().satisfies(entry -> {
            assertThat(entry)
                .containsEntry("mode", "JCMS_PARAM_JSON")
                .containsEntry("historicalPageSize", 15)
                .containsEntry("knownArchiveGapYears", List.of(2024, 2025, 2026));
            assertThat(entry.get("jcmsSearch")).isEqualTo(Map.of(
                "xxgkId", "W001", "xxgkType", "", "className", "人事信息"));
        });
    }

    @Test
    void catalogPublishesZjutHistoryAndHznuCurrentLifecycleWithoutOverstatingCoverage() {
        var catalog = OfficialSourceCatalog.load();
        var zjut = catalog.sources().stream()
            .filter(source -> source.code().equals("ZJUT_RECRUITMENT")).findFirst().orElseThrow();
        var hznu = catalog.sources().stream()
            .filter(source -> source.code().equals("HZNU_RECRUITMENT")).findFirst().orElseThrow();

        assertThat(zjut.enabled()).isTrue();
        assertThat(zjut.strategy()).isEqualTo(OfficialSourceCatalog.DiscoveryStrategy.STATIC_HTML);
        assertThat(zjut.listingEntries()).hasSize(2).allSatisfy(entry -> {
            assertThat(entry)
                .containsEntry("mode", "STATIC_SUFFIX_TEMPLATE")
                .containsEntry("transportPolicy", "AUDITED_HTTP_READ_ONLY")
                .containsEntry("completenessRequired", false)
                .containsEntry("reconcileReportedTotalByListingItems", true);
            assertThat(entry.get("exactAuthorities")).isEqualTo(List.of("www.rczp.zjut.edu.cn:80"));
        });
        assertThat(zjut.listingEntries()).extracting(entry -> entry.get("code"))
            .containsExactly("recruitment-announcements", "appointment-publicity");

        assertThat(hznu.enabled()).isTrue();
        assertThat(hznu.strategy()).isEqualTo(OfficialSourceCatalog.DiscoveryStrategy.STATIC_HTML);
        assertThat(hznu.listingEntries()).singleElement().satisfies(entry -> assertThat(entry)
            .containsEntry("mode", "LINKED_PAGE")
            .containsEntry("role", "CAMPAIGN_STATE")
            .containsEntry("completenessRequired", false)
            .containsEntry("knownArchiveGapYears", List.of(2024, 2025, 2026)));
    }

    @Test
    void catalogPublishesFirstHospitalAsAnExactAuthorityMultiEntrySource() {
        var hospital = OfficialSourceCatalog.load().sources().stream()
            .filter(source -> source.code().equals("HZ_FIRST_HOSPITAL"))
            .findFirst().orElseThrow();

        assertThat(hospital.enabled()).isTrue();
        assertThat(hospital.historicalYears()).containsExactly(2024, 2025, 2026, 2027);
        assertThat(hospital.listingUrl()).isNull();
        assertThat(hospital.configuration())
            .containsEntry("imageEvidenceSelector", "img[src*='file_zp/attached/']")
            .doesNotContainKeys("historicalEvidenceByYear", "historicalPaginationMode");
        assertThat(hospital.listingEntries()).singleElement().satisfies(entry -> {
            assertThat(entry)
                .containsEntry("code", "official-notices")
                .containsEntry("mode", "QUERY_PAGE")
                .containsEntry("transportPolicy", "AUDITED_HTTP_READ_ONLY")
                .containsEntry("reportedTotalRegex", "共\\s*(\\d+)\\s*条")
                .containsEntry("reportedTotalPagesRegex", "共\\s*(\\d+)\\s*页");
            assertThat(entry.get("entryUri")).isEqualTo(
                "http://124.160.72.42:8080/apply/getMore.action?pageNumber=1");
            assertThat(entry.get("exactAuthorities")).isEqualTo(List.of("124.160.72.42:8080"));
            assertThat(entry.get("allowedPathPrefixes")).isEqualTo(List.of(
                "/apply/getMore.action", "/apply/getNotice.action",
                "/apply/downloadAccessory.action", "/file_zp/attached/"));
        });
        assertThat(hospital.configuration().toString())
            .doesNotContain("zp.hz-hospital.com", "renshi.wechathospital.com");
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
            .allMatch(source -> source.listingUrl() != null && !source.listingUrl().isBlank()
                || !source.listingEntries().isEmpty());
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
        assertThat(hospital.listingEntries()).isNotEmpty();
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
              <a href='/col/col1229349919/art/2025/art_b900cfc4dba7403887a8cc0a165aadf2.html'>
                2025年杭州市西湖区部分事业单位公开招聘工作人员拟聘用人员公示</a>
            </div>"}}
            """.getBytes(StandardCharsets.UTF_8);

        assertThat(new StaticHtmlSourceDiscoverer().discover(source, response))
            .extracting(link -> link.uri().toString())
            .containsExactly(
                "https://www.hzxh.gov.cn/col/col1229349919/art/2025/art_a900cfc4dba7403887a8cc0a165aadf1.html",
                "https://www.hzxh.gov.cn/col/col1368377/art/2026/art_fda023d7591c4fbf820b53260da8962a.html",
                "https://www.hzxh.gov.cn/col/col1229349919/art/2025/art_b900cfc4dba7403887a8cc0a165aadf2.html");
    }

    @Test
    void gongshuJcmsContractKeepsInitialAndLifecycleNoticesButRejectsExternalAggregates() {
        var source = OfficialSourceCatalog.load().sources().stream()
            .filter(value -> value.code().equals("HZ_GONGSHU_GOV")).findFirst().orElseThrow();
        byte[] response = """
            {"success":true,"data":{"html":"<div class='default_pgContainer'>
              <a href='/col/col1229226160/art/2026/art_8be90ae6320b4263859b753cdacf43f4.html'>
                2026年杭州市拱墅区卫生健康局事业单位公开招聘工作人员公告</a>
              <a href='/col/col1229226160/art/2026/art_a78ec64bb23a43c29f53f0411821ba3f.html'>
                2026年拱墅区卫生健康局公开招聘事业单位工作人员综合成绩公示</a>
              <a href='https://hrss.hangzhou.gov.cn/art/2024/1/1/art_external.html'>
                杭州市事业单位统一招聘公告</a>
            </div>","count":"8"}}
            """.getBytes(StandardCharsets.UTF_8);

        assertThat(new StaticHtmlSourceDiscoverer().discover(source, response))
            .extracting(link -> link.uri().toString())
            .containsExactly(
                "https://www.gongshu.gov.cn/col/col1229226160/art/2026/art_8be90ae6320b4263859b753cdacf43f4.html",
                "https://www.gongshu.gov.cn/col/col1229226160/art/2026/art_a78ec64bb23a43c29f53f0411821ba3f.html");
    }

    @Test
    void qiantangJcmsContractAcceptsCurrentAndLegacyRecruitmentLifecycleUrls() {
        var source = OfficialSourceCatalog.load().sources().stream()
            .filter(value -> value.code().equals("HZ_QIANTANG_GOV")).findFirst().orElseThrow();
        byte[] response = """
            {"success":true,"data":{"html":"<div count='2902'>
              <a href='/col/col1657687/art/2026/art_8be90ae6320b4263859b753cdacf43f4.html'>
                2026年钱塘区事业单位公开招聘工作人员公告</a>
              <a href='/art/2025/5/6/art_1657687_58975146.html'>
                2025年钱塘新区管理委员会紧缺岗位政府雇员公开招聘拟聘人员公示</a>
              <a href='/col/col1657687/art/2026/art_f333.html'>关于道路项目的公示</a>
            </div>"}}
            """.getBytes(StandardCharsets.UTF_8);

        assertThat(new StaticHtmlSourceDiscoverer().discover(source, response))
            .extracting(link -> link.uri().toString())
            .containsExactly(
                "https://www.qiantang.gov.cn/col/col1657687/art/2026/art_8be90ae6320b4263859b753cdacf43f4.html",
                "https://www.qiantang.gov.cn/art/2025/5/6/art_1657687_58975146.html");
    }
}
