package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.acquisition.RecruitmentSource.CrawlMode;
import com.careeros.domain.acquisition.RecruitmentSource.SourceType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HtmlAttachmentDiscovererTest {
    @Test
    void findsSupportedAttachmentsOnOfficialAndConfiguredHostsOnly() {
        byte[] html = """
            <html><body>
              <a href="/files/jobs.xlsx?utm_source=page">招聘计划表</a>
              <a href="https://zjjcmspublic.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn/files/guide.pdf">报考指南</a>
              <a href="https://external.example/jobs.xlsx">外部转载</a>
              <a href="/files/readme.docx">说明</a>
              <a href="/files/jobs.xlsx">招聘计划表</a>
              <a href="/api-gateway/jpaas-web-server/front/document/download?fileUrl=token%2Fvalue%3D&amp;fileName=%E8%AE%A1%E5%88%92%E8%A1%A8.xlsx">政务网招聘计划表</a>
            </body></html>
            """.getBytes(StandardCharsets.UTF_8);

        var links = new HtmlAttachmentDiscoverer().discover(source(),
            URI.create("https://rlsbt.zj.gov.cn/art/2026/3/17/notice.html"), html);

        assertThat(links).extracting(link -> link.uri().toString()).containsExactly(
            "https://rlsbt.zj.gov.cn/api-gateway/jpaas-web-server/front/document/download?fileName=%E8%AE%A1%E5%88%92%E8%A1%A8.xlsx&fileUrl=token%2Fvalue%3D",
            "https://rlsbt.zj.gov.cn/files/jobs.xlsx",
            "https://zjjcmspublic.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn/files/guide.pdf");
    }

    private static RecruitmentSource source() {
        Instant now = Instant.parse("2026-08-15T00:00:00Z");
        return new RecruitmentSource(UUID.randomUUID(), "ZJ", "浙江人社",
            URI.create("https://rlsbt.zj.gov.cn/"), URI.create("https://rlsbt.zj.gov.cn/list"),
            SourceType.OFFICIAL_GOVERNMENT, "浙江", CrawlMode.STATIC_HTML, true,
            "0 10 8 * * *", "Asia/Shanghai", Duration.ofSeconds(1), Map.of(
                "attachmentSelector", "a[href]",
                "allowedHosts", List.of("zjjcmspublic.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn")),
            null, null, now, 0, now, now);
    }
}
