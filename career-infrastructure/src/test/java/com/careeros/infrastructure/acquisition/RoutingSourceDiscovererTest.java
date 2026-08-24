package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.acquisition.RecruitmentSource.CrawlMode;
import com.careeros.domain.acquisition.RecruitmentSource.SourceType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoutingSourceDiscovererTest {
    private final RoutingSourceDiscoverer discoverer = new RoutingSourceDiscoverer(
        new StaticHtmlSourceDiscoverer(), new HospitalOfficialEvidenceDiscoverer());

    @Test
    void hospitalAdapterReturnsOnlyAllowlistedHttpsEvidence() {
        byte[] html = """
            <a href="https://www.hz-hospital.com/content/details/id/228230?cid=68">公开招聘编外工作人员</a>
            <a href="http://zhaopin.hz-hospital.com:8080/">进入报名系统</a>
            <a href="https://example.com/recruitment">外部招聘信息</a>
            """.getBytes(StandardCharsets.UTF_8);

        var links = discoverer.discover(source("HOSPITAL_OFFICIAL_EVIDENCE"),
            URI.create("https://www.hz-hospital.com/"), html);

        assertThat(links).singleElement().satisfies(link ->
            assertThat(link.uri()).isEqualTo(
                URI.create("https://www.hz-hospital.com/content/details/id/228230?cid=68")));
    }

    @Test
    void unknownAdapterFailsInsteadOfFallingBack() {
        assertThatThrownBy(() -> discoverer.discover(source("UNKNOWN_ADAPTER"),
            URI.create("https://www.hz-hospital.com/"), "<html/>".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("UNKNOWN_ADAPTER");
    }

    private static RecruitmentSource source(String adapterType) {
        Instant now = Instant.parse("2026-08-24T12:00:00Z");
        return new RecruitmentSource(
            UUID.fromString("01992f09-0000-7000-8000-000000000305"),
            "HZ_FIRST_HOSPITAL", "杭州市第一人民医院招聘",
            URI.create("https://www.hz-hospital.com/"),
            URI.create("https://www.hz-hospital.com/"),
            SourceType.OFFICIAL_ORGANIZATION, "杭州", CrawlMode.STATIC_HTML,
            true, "0 50 8 * * *", "Asia/Shanghai", Duration.ofMillis(1_500),
            Map.of(
                "adapterType", adapterType,
                "articleUrlRegex", "^https://www\\.hz-hospital\\.com/content/details/id/[0-9]+(?:\\?cid=[0-9]+)?$",
                "linkSelector", "a[href]",
                "titleIncludeRegex", "招聘|招贤|人才",
                "titleExcludeRegex", "拟聘|公示|成绩|体检|递补"
            ), null, null, now, 0, now, now);
    }
}
