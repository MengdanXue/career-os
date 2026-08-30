package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.AcquisitionHttpPorts.DiscoveredLink;
import com.careeros.application.AcquisitionHttpPorts.HttpReadContract;
import com.careeros.application.AcquisitionHttpPorts.TransportPolicy;
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
    void auditedHttpParentAllowsOnlyAttachmentsInsideItsExactContractAndPropagatesIt() {
        var contract = new HttpReadContract(TransportPolicy.AUDITED_HTTP_READ_ONLY,
            java.util.Set.of("legacy.example"), java.util.Set.of("/public/jobs"));
        var parent = new DiscoveredLink(
            URI.create("http://legacy.example/public/jobs/notice.html"), "招聘公告", contract);
        byte[] html = """
            <a href='/public/jobs/files/plan.xlsx'>岗位表</a>
            <a href='/private/plan.xlsx'>越权路径</a>
            <a href='http://other.example/public/jobs/plan.xlsx'>越权主机</a>
            <a href='/public/jobs/%252e%252e/private/plan.xlsx'>编码绕过</a>
            """.getBytes(StandardCharsets.UTF_8);

        var links = new HtmlAttachmentDiscoverer().discover(source(), parent, html);

        assertThat(links).singleElement().satisfies(link -> {
            assertThat(link.uri())
                .isEqualTo(URI.create("http://legacy.example/public/jobs/files/plan.xlsx"));
            assertThat(link.readContract()).isEqualTo(contract);
        });
    }

    @Test
    void auditedHospitalContractDiscoversAuthorizedExtensionlessAccessoryDownload() {
        var contract = new HttpReadContract(TransportPolicy.AUDITED_HTTP_READ_ONLY,
            java.util.Set.of("124.160.72.42"),
            java.util.Set.of("124.160.72.42:8080"),
            java.util.Set.of("/apply/getNotice.action", "/apply/downloadAccessory.action"));
        var parent = new DiscoveredLink(
            URI.create("http://124.160.72.42:8080/apply/getNotice.action?keycode=notice"),
            "招聘公告", contract);
        byte[] html = """
            <a href='/apply/downloadAccessory.action?keycode=attachment'>岗位附件</a>
            <a href='/apply/other.action?keycode=attachment'>越权下载</a>
            """.getBytes(StandardCharsets.UTF_8);

        var links = new HtmlAttachmentDiscoverer().discover(source(), parent, html);

        assertThat(links).singleElement().satisfies(link -> {
            assertThat(link.uri()).isEqualTo(URI.create(
                "http://124.160.72.42:8080/apply/downloadAccessory.action?keycode=attachment"));
            assertThat(link.title()).isEqualTo("岗位附件");
            assertThat(link.readContract()).isEqualTo(contract);
        });
    }

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

    @Test
    void discoversOnlyConfiguredInlineJobTableImagesWithoutChangingAttachmentDiscovery() {
        byte[] html = """
            <html><body>
              <img class="site-logo" src="/images/logo.png" alt="站点标志">
              <div class="content">
                <img class="job-table" src="/ueditor/php/upload/image/jobs.jpeg" alt="招聘岗位表">
                <a href="/files/requirements.pdf">报考要求</a>
              </div>
            </body></html>
            """.getBytes(StandardCharsets.UTF_8);
        RecruitmentSource configured = source(Map.of(
            "attachmentSelector", "a[href]",
            "imageEvidenceSelector", ".content img.job-table",
            "allowedHosts", List.of(
                "zjjcmspublic.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn")));

        var links = new HtmlAttachmentDiscoverer().discover(configured,
            URI.create("https://rlsbt.zj.gov.cn/art/2026/3/17/notice.html"), html);

        assertThat(links).extracting(link -> link.uri().toString()).containsExactly(
            "https://rlsbt.zj.gov.cn/files/requirements.pdf",
            "https://rlsbt.zj.gov.cn/ueditor/php/upload/image/jobs.jpeg");
        assertThat(links).extracting(DiscoveredLink::title)
            .containsExactly("报考要求", "招聘岗位表");
    }

    @Test
    void ignoresInlineImagesWhenTheSourceHasNoImageEvidenceSelector() {
        byte[] html = """
            <div class="content"><img src="/images/jobs.png" alt="岗位表"></div>
            """.getBytes(StandardCharsets.UTF_8);

        var links = new HtmlAttachmentDiscoverer().discover(source(),
            URI.create("https://rlsbt.zj.gov.cn/art/2026/3/17/notice.html"), html);

        assertThat(links).isEmpty();
    }

    @Test
    void discoversPipeSeparatedOfficialAttachmentsFromOneConfiguredScriptVariable() {
        var contract = new HttpReadContract(TransportPolicy.HTTPS_ONLY,
            java.util.Set.of("him.cas.cn"), java.util.Set.of("/rczp/zpgg/"));
        var parent = new DiscoveredLink(
            URI.create("https://him.cas.cn/rczp/zpgg/202604/t20260409_123.html"),
            "工程技术岗位招聘", contract);
        byte[] html = """
            <script>
              var unrelated = './do-not-collect.pdf';
              var appLinkStr='./P020260409691586818094.xlsx|https://evil.example/jobs.pdf|../../outside.pdf';
            </script>
            """.getBytes(StandardCharsets.UTF_8);
        RecruitmentSource configured = source(Map.of(
            "attachmentSelector", "a[href]",
            "scriptAttachmentVariable", "appLinkStr",
            "allowedHosts", List.of("him.cas.cn")));

        var links = new HtmlAttachmentDiscoverer().discover(configured, parent, html);

        assertThat(links).singleElement().satisfies(link -> {
            assertThat(link.uri()).isEqualTo(URI.create(
                "https://him.cas.cn/rczp/zpgg/202604/P020260409691586818094.xlsx"));
            assertThat(link.readContract()).isEqualTo(contract);
        });
    }

    private static RecruitmentSource source() {
        return source(Map.of(
            "attachmentSelector", "a[href]",
            "allowedHosts", List.of(
                "zjjcmspublic.oss-cn-hangzhou-zwynet-d01-a.internet.cloud.zj.gov.cn")));
    }

    private static RecruitmentSource source(Map<String, Object> configuration) {
        Instant now = Instant.parse("2026-08-15T00:00:00Z");
        return new RecruitmentSource(UUID.randomUUID(), "ZJ", "浙江人社",
            URI.create("https://rlsbt.zj.gov.cn/"), URI.create("https://rlsbt.zj.gov.cn/list"),
            SourceType.OFFICIAL_GOVERNMENT, "浙江", CrawlMode.STATIC_HTML, true,
            "0 10 8 * * *", "Asia/Shanghai", Duration.ofSeconds(1), configuration,
            null, null, now, 0, now, now);
    }
}
