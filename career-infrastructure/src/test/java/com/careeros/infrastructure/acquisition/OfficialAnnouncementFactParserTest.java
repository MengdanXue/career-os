package com.careeros.infrastructure.acquisition;

import static com.careeros.domain.GraduateEligibilityRule.CohortScope.CURRENT_YEAR;
import static com.careeros.domain.GraduateEligibilityRule.CohortScope.PREVIOUS_YEAR;
import static com.careeros.domain.GraduateEligibilityRule.CohortScope.TWO_YEARS_PRIOR;
import static com.careeros.domain.GraduateEligibilityRule.EvidenceState.CONFIRMED;
import static com.careeros.domain.GraduateEligibilityRule.RequirementTiming.QUALIFICATION_REVIEW;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.domain.GraduateEligibilityRule;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class OfficialAnnouncementFactParserTest {
    private final OfficialAnnouncementFactParser parser = new OfficialAnnouncementFactParser();

    @Test
    void parsesDecisionFactsFromHangzhouUnifiedRecruitmentAnnouncement() {
        String html = """
            <html><head><meta name="PubDate" content="2026-03-17 17:12"></head><body>
            <div class="article-content">
              <p>年龄一般为38周岁以下（1987年3月19日以后出生），其他有关资格条件或工作经历的计算，截止时间为2026年3月19日。</p>
              <p>报名时间：2026年3月19日9：00－3月25日16：00。</p>
              <p>资格初审时间：2026年3月19日9：00－3月26日17：00。</p>
              <p>缴费确认时间：2026年3月19日9：00－3月27日24：00。</p>
              <p>网址：http://qssy.zjks.com（浙江省通用招聘网报平台）。</p>
              <p>下载并打印准考证时间：2026年4月20日－4月25日。</p>
              <p>2024年、2025年和2026年普通高校毕业生，含同期毕业的留学回国人员。2026年普通高校毕业生取得相应证书的时限为2026年9月30日前。</p>
              <p>岗位要求的“工作经历”以签订的劳动（聘用）合同、社保缴费记录及其他有效证明为准。</p>
              <p>国外学历学位需取得教育部留学服务中心学历学位认证。</p>
              <p>笔试时间：2026年4月25日 上午9:00—11:30 《综合应用能力》 下午2:00—3:30 《职业能力倾向测验》。</p>
              <p>考试包括笔试和面试。面试时间、地点另行通知，可包括专业知识测试、实际操作考试、结构化面试等形式。</p>
              <p>经公示无异议的，办理相关手续，签订聘用合同。</p>
            </div></body></html>
            """;

        var facts = parser.parse(html, "https://hrss.hangzhou.gov.cn/notice.html");

        assertThat(facts.publishedOn()).isEqualTo(LocalDate.of(2026, 3, 17));
        assertThat(facts.applicationStartsAt()).isEqualTo(OffsetDateTime.parse("2026-03-19T09:00:00+08:00"));
        assertThat(facts.applicationEndsAt()).isEqualTo(OffsetDateTime.parse("2026-03-25T16:00:00+08:00"));
        assertThat(facts.qualificationReviewEndsOn()).isEqualTo(OffsetDateTime.parse("2026-03-26T17:00:00+08:00"));
        assertThat(facts.paymentEndsOn()).isEqualTo(OffsetDateTime.parse("2026-03-28T00:00:00+08:00"));
        assertThat(facts.ageReferenceDate()).isEqualTo(LocalDate.of(2026, 3, 19));
        assertThat(facts.registrationUrl()).isEqualTo("http://qssy.zjks.com");
        assertThat(facts.admissionTicketStartsOn()).isEqualTo(LocalDate.of(2026, 4, 20));
        assertThat(facts.admissionTicketEndsOn()).isEqualTo(LocalDate.of(2026, 4, 25));
        assertThat(facts.writtenExamOn()).isEqualTo(LocalDate.of(2026, 4, 25));
        assertThat(facts.writtenExamSubjects()).containsExactly("综合应用能力", "职业能力倾向测验");
        assertThat(facts.graduateRule()).contains("2024年、2025年和2026年").contains("2026年9月30日");
        assertThat(facts.graduateEligibilityRule().cohorts())
            .containsExactlyInAnyOrder(CURRENT_YEAR, PREVIOUS_YEAR, TWO_YEARS_PRIOR);
        assertThat(facts.graduateEligibilityRule().includesOverseasGraduates()).isTrue();
        assertThat(facts.graduateEligibilityRule().degreeDeadline()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(facts.writtenExamState()).isEqualTo(CONFIRMED);
        assertThat(facts.interviewState()).isEqualTo(CONFIRMED);
        assertThat(facts.interviewOn()).isNull();
        assertThat(facts.overseasDegreeRule()).contains("教育部留学服务中心学历学位认证");
        assertThat(facts.experienceEvidenceRule()).contains("社保缴费记录");
        assertThat(facts.employmentStatement()).contains("签订聘用合同");
        assertThat(facts.interviewRule()).contains("结构化面试");
        assertThat(facts.evidenceExcerpts()).containsKeys("applicationPeriod", "ageReferenceDate", "overseasDegreeRule", "employmentStatement");
        assertThat(facts.processFacts().notice().state()).isEqualTo(CONFIRMED);
        assertThat(facts.processFacts().application().state()).isEqualTo(CONFIRMED);
        assertThat(facts.processFacts().qualificationReview().state()).isEqualTo(CONFIRMED);
        assertThat(facts.processFacts().payment().state()).isEqualTo(CONFIRMED);
        assertThat(facts.processFacts().admissionTicket().state()).isEqualTo(CONFIRMED);
        assertThat(facts.processFacts().physicalExam().state()).isEqualTo(GraduateEligibilityRule.EvidenceState.NOT_COLLECTED);
        assertThat(facts.processFacts().investigation().state()).isEqualTo(GraduateEligibilityRule.EvidenceState.NOT_COLLECTED);
        assertThat(facts.processFacts().publication().state()).isEqualTo(CONFIRMED);
        assertThat(facts.processFacts().publication().detail()).contains("公示无异议");
        assertThat(facts.processFacts().appointment().state()).isEqualTo(CONFIRMED);
    }

    @Test
    void parsesExamFactsWhenTheHeadingAndTimeAreSeparateParagraphs() {
        String html = """
            <html><head><meta name="PubDate" content="2026-03-17 17:12"></head><body>
              <p>（二）下载并打印准考证</p><p>时间：2026年4月20日－4月25日。</p>
              <p><strong>四、考试</strong></p><p>考试包括笔试和面试。</p>
              <p>（一）笔试</p><p>时间：2026年4月25日</p>
              <p>上午9:00—11:30 《综合应用能力》</p>
              <p>下午2:00—3:30 《职业能力倾向测验》</p>
              <p>题型：《综合应用能力》为主观题，《职业能力倾向测验》为客观题。</p>
              <p>按《招聘计划表》确定面试对象，考试大纲见《关于做好考试考务工作的通知》。</p>
              <p>（二）面试</p><p>面试由招聘单位组织。</p>
            </body></html>
            """;

        var facts = parser.parse(html, "https://hrss.hangzhou.gov.cn/notice.html");

        assertThat(facts.admissionTicketStartsOn()).isEqualTo(LocalDate.of(2026, 4, 20));
        assertThat(facts.admissionTicketEndsOn()).isEqualTo(LocalDate.of(2026, 4, 25));
        assertThat(facts.writtenExamOn()).isEqualTo(LocalDate.of(2026, 4, 25));
        assertThat(facts.writtenExamSubjects()).containsExactly("综合应用能力", "职业能力倾向测验");
    }

    @Test
    void parsesGeneric2027GraduateWordingAndExplicitEmploymentRestrictions() {
        String html = """
            <html><head><meta name="PubDate" content="2027-02-18 09:00"></head><body>
              <p>招聘对象包括2027届、2026届及2025届普通高校毕业生，以及同期国（境）外高校毕业生。</p>
              <p>上述人员资格复审前须取得相应学历学位证书及教育部留学服务中心认证。</p>
              <p>要求未落实工作单位、未缴纳社会保险。</p>
            </body></html>
            """;

        var facts = parser.parse(html, "https://example.gov.cn/2027-notice.html");

        assertThat(facts.graduateEligibilityRule()).isNotNull();
        assertThat(facts.graduateEligibilityRule().cohorts())
            .containsExactlyInAnyOrder(CURRENT_YEAR, PREVIOUS_YEAR, TWO_YEARS_PRIOR);
        assertThat(facts.graduateEligibilityRule().includesOverseasGraduates()).isTrue();
        assertThat(facts.graduateEligibilityRule().degreeTiming()).isEqualTo(QUALIFICATION_REVIEW);
        assertThat(facts.graduateEligibilityRule().credentialTiming()).isEqualTo(QUALIFICATION_REVIEW);
        assertThat(facts.graduateEligibilityRule().requiresNoEmployer()).isTrue();
        assertThat(facts.graduateEligibilityRule().restrictsSocialInsurance()).isTrue();
    }

    @Test
    void keepsCredentialTimingAndDeadlineInsideTheCredentialClause() {
        String html = """
            <html><head><meta name="PubDate" content="2027-02-18 09:00"></head><body>
              <p>2027届毕业生于2027年10月报名。</p>
              <p>国（境）外毕业生的教育部留学服务中心认证须于2028年1月31日前、聘用前取得。</p>
            </body></html>
            """;

        var facts = parser.parse(html, "https://example.gov.cn/credential-timing.html");

        assertThat(facts.graduateEligibilityRule().credentialTiming())
            .isEqualTo(GraduateEligibilityRule.RequirementTiming.APPOINTMENT);
        assertThat(facts.graduateEligibilityRule().credentialDeadline())
            .isEqualTo(LocalDate.of(2028, 1, 31));
    }
}
