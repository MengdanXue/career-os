package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class HospitalOfficialPageParserTest {
    @Test
    void parsesPublicHospitalJobTableAndPreservesHttpApplicationUrlAsEvidenceOnly() {
        byte[] html;
        try (var input = getClass().getResourceAsStream(
            "/official-fixtures/hz-first-hospital-2024-sanitized.html")) {
            html = java.util.Objects.requireNonNull(input).readAllBytes();
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }

        var parsed = new HospitalOfficialPageParser().parse(URI.create(
            "https://zp.hz-hospital.com/index/index/announcement_desc/id/212.html"), html);

        assertThat(parsed.title()).contains("2024年公开招聘");
        assertThat(parsed.publishedOn()).isEqualTo(LocalDate.of(2024, 3, 8));
        assertThat(parsed.applicationUrl()).isEqualTo("http://zhaopin.hz-hospital.com:8080/");
        assertThat(parsed.completeSnapshot()).isTrue();
        assertThat(parsed.jobs()).singleElement().satisfies(job -> {
            assertThat(job.department()).isEqualTo("X-信息中心");
            assertThat(job.title()).isEqualTo("信息工作人员");
            assertThat(job.majors()).contains("计算机科学与技术");
            assertThat(job.candidateScope()).contains("应届毕业生");
        });
    }

    @Test
    void pageWithoutRequiredJobTableRemainsAnnouncementOnlyEvidence() {
        byte[] html = "<html><title>后续事项通知</title><body>面试时间另行通知</body></html>"
            .getBytes(StandardCharsets.UTF_8);

        var parsed = new HospitalOfficialPageParser().parse(URI.create(
            "https://zp.hz-hospital.com/index/index/announcement_desc/id/231.html"), html);

        assertThat(parsed.jobs()).isEmpty();
        assertThat(parsed.issues()).isEmpty();
    }

    @Test
    void malformedJobRowIsReportedAndPreventsCompleteSnapshot() {
        byte[] html = """
            <html><title>杭州市第一人民医院2024年公开招聘</title><body><table>
            <tr><th>科室</th><th>岗位名称</th><th>岗位类别</th><th>学历</th><th>专业</th>
            <th>招聘对象</th><th>人数</th><th>年龄</th></tr>
            <tr><td>信息中心</td><td>系统工程师</td><td>专业技术</td><td>硕士</td>
            <td>计算机科学与技术</td><td>应届毕业生</td><td>1</td><td>38周岁以下</td></tr>
            <tr><td>数据中心</td><td></td><td>专业技术</td><td>硕士</td>
            <td>软件工程</td><td>应届毕业生</td><td>1</td><td>38周岁以下</td></tr>
            </table></body></html>
            """.getBytes(StandardCharsets.UTF_8);

        var parsed = new HospitalOfficialPageParser().parse(URI.create(
            "https://zp.hz-hospital.com/index/index/announcement_desc/id/212.html"), html);

        assertThat(parsed.jobs()).hasSize(1);
        assertThat(parsed.completeSnapshot()).isFalse();
        assertThat(parsed.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.rowNumber()).isEqualTo(3);
            assertThat(issue.errorCode()).isEqualTo("MISSING_JOB_TITLE");
        });
    }

    @Test
    void preservesRowSpecificEmployerEmploymentAndHospitalCampus() {
        byte[] html = """
            <html><title>杭州市第一人民医院2027年招聘</title><body><table>
            <tr><th>科室</th><th>岗位名称</th><th>岗位类别</th><th>学历</th><th>专业</th>
            <th>招聘对象</th><th>人数</th><th>年龄</th><th>实际用人单位</th><th>工作院区</th><th>用工性质</th></tr>
            <tr><td>信息中心</td><td>系统工程师</td><td>专业技术</td><td>硕士</td><td>计算机科学与技术</td>
            <td>应届毕业生</td><td>1</td><td>38周岁以下</td><td>杭州市第一人民医院</td><td>湖滨院区</td><td>事业编制</td></tr>
            <tr><td>数据中心</td><td>数据工程师</td><td>专业技术</td><td>硕士</td><td>软件工程</td>
            <td>应届毕业生</td><td>1</td><td>38周岁以下</td><td>杭州市第一人民医院</td><td>城北院区</td><td>员额制</td></tr>
            </table></body></html>
            """.getBytes(StandardCharsets.UTF_8);

        var parsed = new HospitalOfficialPageParser().parse(URI.create(
            "https://zp.hz-hospital.com/index/index/announcement_desc/id/999.html"), html);

        assertThat(parsed.jobs()).extracting(HospitalOfficialPageParser.HospitalJobRow::worksite)
            .containsExactly("湖滨院区", "城北院区");
        assertThat(parsed.jobs()).extracting(HospitalOfficialPageParser.HospitalJobRow::actualEmployer)
            .containsOnly("杭州市第一人民医院");
        assertThat(parsed.jobs()).extracting(HospitalOfficialPageParser.HospitalJobRow::employmentText)
            .containsExactly("事业编制", "员额制");
    }
}
