package com.careeros.infrastructure.acquisition;

import com.careeros.domain.GraduateEligibilityRule;
import com.careeros.domain.GraduateEligibilityRule.EvidenceState;
import com.careeros.domain.GraduateEligibilityRule.RequirementTiming;
import com.careeros.domain.RecruitmentProcessFacts;
import com.careeros.domain.RecruitmentProcessFacts.ProcessStage;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;

public final class OfficialAnnouncementFactParser {
    private static final ZoneOffset CHINA = ZoneOffset.ofHours(8);
    private static final Pattern META_DATE = Pattern.compile("(20\\d{2})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern PERIOD = Pattern.compile(
        "(20\\d{2})年(\\d{1,2})月(\\d{1,2})日(\\d{1,2})[：:](\\d{2})\\s*[－—-]\\s*(?:(20\\d{2})年)?(\\d{1,2})月(\\d{1,2})日(\\d{1,2})[：:](\\d{2})");
    private static final Pattern DATE_PERIOD = Pattern.compile(
        "(20\\d{2})年(\\d{1,2})月(\\d{1,2})日\\s*[－—-]\\s*(?:(20\\d{2})年)?(\\d{1,2})月(\\d{1,2})日");
    private static final Pattern AGE_REFERENCE = Pattern.compile("(?:截止时间为|截止到|截至)(20\\d{2})年(\\d{1,2})月(\\d{1,2})日");
    private static final Pattern URL = Pattern.compile("网址[：:]\\s*(https?://[^\\s（(。；;]+)");
    private static final Pattern SUBJECT = Pattern.compile("《([^》]+)》");
    private static final Pattern GRADUATION_YEAR = Pattern.compile("(20\\d{2})(?:年|届)");
    private static final Pattern NO_EMPLOYER = Pattern.compile("未落实工作单位|无工作单位|无劳动关系|未(?:与|同).{0,20}(?:建立|签订).{0,10}(?:劳动关系|劳动合同)");
    private static final Pattern SOCIAL_INSURANCE_RESTRICTION = Pattern.compile("(?:未缴纳|无缴纳|不缴纳|无).{0,8}(?:社会保险|社保)|(?:社会保险|社保).{0,8}(?:未缴纳|无缴纳|不缴纳|为空)");

    public OfficialAnnouncementFacts parse(String html, String sourceUrl) {
        if (html == null || html.isBlank()) throw new IllegalArgumentException("html is required");
        if (sourceUrl == null || sourceUrl.isBlank()) throw new IllegalArgumentException("sourceUrl is required");
        var document = Jsoup.parse(html, sourceUrl);
        String text = document.text().replace('\u00a0', ' ').replaceAll("\\s+", " ").strip();
        LocalDate published = date(document.select("meta[name=PubDate]").attr("content"), META_DATE);
        Period application = period(sentence(text, "报名时间"));
        Period review = period(sentence(text, "资格初审时间"));
        Period payment = period(sentence(text, "缴费确认时间"));
        DatePeriod ticket = datePeriod(sentence(text, "打印准考证"));
        String examSentence = sentence(text, "笔试时间");
        String examSection = section(text, "（一）笔试", "（二）面试");
        String examText = examSentence == null ? examSection : examSentence;
        LocalDate examDate = firstChineseDate(examText);
        var subjects = new LinkedHashSet<String>();
        String subjectText = before(examText, "题型：", "题型:", "按《招聘计划表》", "考试大纲");
        var subjectMatcher = SUBJECT.matcher(subjectText == null ? "" : subjectText);
        while (subjectMatcher.find()) subjects.add(subjectMatcher.group(1));
        String graduate = graduateRuleText(text);
        String overseas = sentence(text, "教育部留学服务中心学历学位认证");
        String experience = sentence(text, "社保缴费记录");
        String employment = sentence(text, "签订聘用合同");
        String interview = joinSentences(text, "考试包括笔试和面试", "结构化面试");
        String physicalExam = joinSentences(text, "体检");
        String investigation = joinSentences(text, "考察");
        String publication = joinSentences(text, "公示");
        GraduateEligibilityRule graduateEligibilityRule = graduateRule(published,
            joinNonBlank(graduate, overseas, sentence(text, "未落实工作单位"), sentence(text, "社会保险")));
        EvidenceState writtenExamState = processState(text, "笔试", examText, examDate != null || !subjects.isEmpty());
        EvidenceState professionalTestState = professionalTestState(interview);
        EvidenceState interviewState = processState(text, "面试", interview, interview != null);
        String interviewSentence = sentence(text, "面试时间");
        LocalDate interviewOn = firstChineseDate(interviewSentence);
        String ageSentence = sentence(text, "资格条件或工作经历的计算");
        LocalDate ageReference = date(ageSentence, AGE_REFERENCE);
        String applicationSentence = sentence(text, "报名时间");
        var excerpts = new LinkedHashMap<String, String>();
        put(excerpts, "applicationPeriod", applicationSentence);
        put(excerpts, "ageReferenceDate", ageSentence);
        put(excerpts, "graduateRule", graduate);
        put(excerpts, "overseasDegreeRule", overseas);
        put(excerpts, "experienceEvidenceRule", experience);
        put(excerpts, "employmentStatement", employment);
        put(excerpts, "interviewRule", interview);
        put(excerpts, "physicalExamRule", physicalExam);
        put(excerpts, "investigationRule", investigation);
        put(excerpts, "publicationRule", publication);
        var processFacts = new RecruitmentProcessFacts(
            stage(published == null ? EvidenceState.NOT_COLLECTED : EvidenceState.CONFIRMED, null),
            stage(processState(text, "报名", applicationSentence, application.start() != null), applicationSentence),
            stage(processState(text, "资格初审", sentence(text, "资格初审时间"), review.end() != null), sentence(text, "资格初审时间")),
            stage(processState(text, "缴费", sentence(text, "缴费确认时间"), payment.end() != null), sentence(text, "缴费确认时间")),
            stage(processState(text, "准考证", sentence(text, "打印准考证"), ticket.start() != null || ticket.end() != null), sentence(text, "打印准考证")),
            stage(writtenExamState, examText), stage(professionalTestState, interview),
            stage(interviewState, interview),
            stage(processState(text, "体检", physicalExam, physicalExam != null), physicalExam),
            stage(processState(text, "考察", investigation, investigation != null), investigation),
            stage(processState(text, "公示", publication, publication != null), publication),
            stage(processState(text, "聘用", employment, employment != null), employment));
        return new OfficialAnnouncementFacts(
            published, application.start(), application.end(), review.end(), payment.end(), ageReference,
            match(text, URL), ticket.start(), ticket.end(), examDate, List.copyOf(subjects), graduate,
            overseas, experience, employment, interview, Map.copyOf(excerpts), graduateEligibilityRule,
            writtenExamState, professionalTestState, interviewState, interviewOn, interview, null, processFacts);
    }

    private static GraduateEligibilityRule graduateRule(LocalDate publishedOn, String rawRule) {
        if (publishedOn == null || rawRule == null || rawRule.isBlank()) return null;
        if (rawRule.contains("毕业年份不限") || rawRule.contains("不限制毕业年份") || rawRule.contains("不限定届别")) {
            return new GraduateEligibilityRule(publishedOn.getYear(), java.util.Set.of(),
                java.util.Set.of(GraduateEligibilityRule.CohortScope.UNRESTRICTED), includesOverseas(rawRule),
                timingFor(rawRule, false), firstChineseDate(rawRule), timingFor(rawRule, true),
                firstChineseDate(rawRule), NO_EMPLOYER.matcher(rawRule).find(),
                SOCIAL_INSURANCE_RESTRICTION.matcher(rawRule).find(), rawRule, EvidenceState.CONFIRMED);
        }
        var years = new LinkedHashSet<Integer>();
        var matcher = GRADUATION_YEAR.matcher(rawRule);
        while (matcher.find()) years.add(Integer.parseInt(matcher.group(1)));
        var base = GraduateEligibilityRule.fromExplicitYears(
            publishedOn.getYear(), years, includesOverseas(rawRule), rawRule);
        LocalDate degreeDeadline = firstChineseDate(sentence(rawRule, "取得相应证书的时限"));
        if (degreeDeadline == null) degreeDeadline = firstChineseDate(rawRule);
        return new GraduateEligibilityRule(
            base.recruitmentYear(), base.explicitGraduationYears(), base.cohorts(),
            base.includesOverseasGraduates(), timingFor(rawRule, false), degreeDeadline,
            timingFor(rawRule, true), rawRule.contains("认证") ? degreeDeadline : null,
            NO_EMPLOYER.matcher(rawRule).find(), SOCIAL_INSURANCE_RESTRICTION.matcher(rawRule).find(),
            base.rawText(), base.evidenceState());
    }

    private static boolean includesOverseas(String text) {
        return text.contains("留学回国") || text.contains("国（境）外") || text.contains("国(境)外")
            || text.contains("境外高校毕业生") || text.contains("国外高校毕业生");
    }

    private static RequirementTiming timingFor(String text, boolean credential) {
        if (credential && !(text.contains("认证") || text.contains("留学服务中心"))) {
            return RequirementTiming.UNSPECIFIED;
        }
        if (text.contains("报名") || text.contains("资格初审")) return RequirementTiming.APPLICATION;
        if (text.contains("资格复审") || text.contains("资格审查")) return RequirementTiming.QUALIFICATION_REVIEW;
        if (text.contains("录用") || text.contains("聘用")) return RequirementTiming.APPOINTMENT;
        if (text.contains("报到")) return RequirementTiming.REPORTING;
        return RequirementTiming.UNSPECIFIED;
    }

    private static String graduateRuleText(String text) {
        if (text == null || text.isBlank()) return null;
        var values = new ArrayList<String>();
        for (String clause : text.split("(?<=[。；;])")) {
            String normalized = clause.strip();
            boolean cohort = (GRADUATION_YEAR.matcher(normalized).find() && normalized.contains("毕业"))
                || normalized.contains("毕业年份不限") || normalized.contains("不限制毕业年份")
                || normalized.contains("不限定届别");
            boolean relatedRule = normalized.contains("取得相应证书") || normalized.contains("学历学位证书")
                || normalized.contains("留学服务中心") || normalized.contains("未落实工作单位")
                || normalized.contains("社会保险") || normalized.contains("社保");
            if ((cohort || relatedRule) && !values.contains(normalized)) values.add(normalized);
        }
        return values.isEmpty() ? null : String.join(" ", values);
    }

    private static String joinNonBlank(String... values) {
        var result = new ArrayList<String>();
        for (String value : values) {
            if (value != null && !value.isBlank() && !result.contains(value)) result.add(value);
        }
        return result.isEmpty() ? null : String.join(" ", result);
    }

    private static EvidenceState processState(
        String allText,
        String processName,
        String processText,
        boolean confirmed
    ) {
        if (allText.contains("不组织" + processName) || allText.contains("不设" + processName)) {
            return EvidenceState.NOT_REQUIRED;
        }
        if (confirmed) return EvidenceState.CONFIRMED;
        if (allText.contains(processName + "另行通知") || allText.contains(processName + "时间、地点另行通知")) {
            return EvidenceState.NOT_PUBLISHED;
        }
        return processText == null ? EvidenceState.NOT_COLLECTED : EvidenceState.REVIEW_REQUIRED;
    }

    private static ProcessStage stage(EvidenceState state, String detail) {
        return new ProcessStage(state, detail);
    }

    private static EvidenceState professionalTestState(String interviewRule) {
        if (interviewRule == null || !interviewRule.contains("专业") && !interviewRule.contains("技能")) {
            return EvidenceState.NOT_COLLECTED;
        }
        if (interviewRule.contains("可包括") || interviewRule.contains("可能")) {
            return EvidenceState.REVIEW_REQUIRED;
        }
        return EvidenceState.CONFIRMED;
    }

    private static String before(String value, String... markers) {
        if (value == null) return null;
        int end = value.length();
        for (String marker : markers) {
            int index = value.indexOf(marker);
            if (index >= 0 && index < end) end = index;
        }
        return value.substring(0, end);
    }

    private static Period period(String value) {
        var matcher = PERIOD.matcher(value == null ? "" : value);
        if (!matcher.find()) return new Period(null, null);
        int year = integer(matcher, 1);
        int endYear = matcher.group(6) == null ? year : integer(matcher, 6);
        return new Period(
            at(year, integer(matcher, 2), integer(matcher, 3), integer(matcher, 4), integer(matcher, 5)),
            at(endYear, integer(matcher, 7), integer(matcher, 8), integer(matcher, 9), integer(matcher, 10)));
    }

    private static OffsetDateTime at(int year, int month, int day, int hour, int minute) {
        LocalDateTime value = hour == 24
            ? LocalDate.of(year, month, day).plusDays(1).atStartOfDay()
            : LocalDateTime.of(year, month, day, hour, minute);
        return value.atOffset(CHINA);
    }

    private static DatePeriod datePeriod(String value) {
        var matcher = DATE_PERIOD.matcher(value == null ? "" : value);
        if (!matcher.find()) return new DatePeriod(null, null);
        int year = integer(matcher, 1);
        int endYear = matcher.group(4) == null ? year : integer(matcher, 4);
        return new DatePeriod(LocalDate.of(year, integer(matcher, 2), integer(matcher, 3)),
            LocalDate.of(endYear, integer(matcher, 5), integer(matcher, 6)));
    }

    private static LocalDate firstChineseDate(String value) {
        if (value == null) return null;
        var matcher = Pattern.compile("(20\\d{2})年(\\d{1,2})月(\\d{1,2})日").matcher(value);
        return matcher.find() ? LocalDate.of(integer(matcher, 1), integer(matcher, 2), integer(matcher, 3)) : null;
    }

    private static LocalDate date(String value, Pattern pattern) {
        if (value == null) return null;
        var matcher = pattern.matcher(value);
        return matcher.find() ? LocalDate.of(integer(matcher, 1), integer(matcher, 2), integer(matcher, 3)) : null;
    }

    private static int integer(java.util.regex.Matcher matcher, int group) {
        return Integer.parseInt(matcher.group(group));
    }

    private static String match(String value, Pattern pattern) {
        var matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String sentence(String text, String token) {
        if (text == null || token == null) return null;
        int position = text.indexOf(token);
        if (position < 0) return null;
        int start = Math.max(lastDelimiter(text, position) + 1, 0);
        int end = nextDelimiter(text, position);
        return text.substring(start, end).strip();
    }

    private static int lastDelimiter(String value, int before) {
        int result = -1;
        for (char delimiter : new char[] {'。', '；', ';'}) result = Math.max(result, value.lastIndexOf(delimiter, before));
        return result;
    }

    private static int nextDelimiter(String value, int after) {
        int result = value.length();
        for (char delimiter : new char[] {'。', '；', ';'}) {
            int found = value.indexOf(delimiter, after);
            if (found >= 0) result = Math.min(result, found + 1);
        }
        return result;
    }

    private static String joinSentences(String text, String... tokens) {
        var values = new ArrayList<String>();
        for (String token : tokens) {
            String value = sentence(text, token);
            if (value != null && !values.contains(value)) values.add(value);
        }
        return values.isEmpty() ? null : String.join(" ", values);
    }

    private static String section(String text, String startsWith, String endsWith) {
        if (text == null) return null;
        int start = text.indexOf(startsWith);
        if (start < 0) return null;
        int end = text.indexOf(endsWith, start + startsWith.length());
        return text.substring(start, end < 0 ? text.length() : end).strip();
    }

    private static void put(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) values.put(key, value);
    }

    private record Period(OffsetDateTime start, OffsetDateTime end) {}
    private record DatePeriod(LocalDate start, LocalDate end) {}

    public record OfficialAnnouncementFacts(
        LocalDate publishedOn,
        OffsetDateTime applicationStartsAt,
        OffsetDateTime applicationEndsAt,
        OffsetDateTime qualificationReviewEndsOn,
        OffsetDateTime paymentEndsOn,
        LocalDate ageReferenceDate,
        String registrationUrl,
        LocalDate admissionTicketStartsOn,
        LocalDate admissionTicketEndsOn,
        LocalDate writtenExamOn,
        List<String> writtenExamSubjects,
        String graduateRule,
        String overseasDegreeRule,
        String experienceEvidenceRule,
        String employmentStatement,
        String interviewRule,
        Map<String, String> evidenceExcerpts,
        GraduateEligibilityRule graduateEligibilityRule,
        EvidenceState writtenExamState,
        EvidenceState professionalTestState,
        EvidenceState interviewState,
        LocalDate interviewOn,
        String interviewMethod,
        String scoreFormula,
        RecruitmentProcessFacts processFacts
    ) {
        public OfficialAnnouncementFacts(
            LocalDate publishedOn, OffsetDateTime applicationStartsAt, OffsetDateTime applicationEndsAt,
            OffsetDateTime qualificationReviewEndsOn, OffsetDateTime paymentEndsOn, LocalDate ageReferenceDate,
            String registrationUrl, LocalDate admissionTicketStartsOn, LocalDate admissionTicketEndsOn,
            LocalDate writtenExamOn, List<String> writtenExamSubjects, String graduateRule,
            String overseasDegreeRule, String experienceEvidenceRule, String employmentStatement,
            String interviewRule, Map<String, String> evidenceExcerpts,
            GraduateEligibilityRule graduateEligibilityRule, EvidenceState writtenExamState,
            EvidenceState professionalTestState, EvidenceState interviewState, LocalDate interviewOn,
            String interviewMethod, String scoreFormula
        ) {
            this(publishedOn, applicationStartsAt, applicationEndsAt, qualificationReviewEndsOn, paymentEndsOn,
                ageReferenceDate, registrationUrl, admissionTicketStartsOn, admissionTicketEndsOn, writtenExamOn,
                writtenExamSubjects, graduateRule, overseasDegreeRule, experienceEvidenceRule, employmentStatement,
                interviewRule, evidenceExcerpts, graduateEligibilityRule, writtenExamState, professionalTestState,
                interviewState, interviewOn, interviewMethod, scoreFormula, null);
        }

        public OfficialAnnouncementFacts(
            LocalDate publishedOn,
            OffsetDateTime applicationStartsAt,
            OffsetDateTime applicationEndsAt,
            OffsetDateTime qualificationReviewEndsOn,
            OffsetDateTime paymentEndsOn,
            LocalDate ageReferenceDate,
            String registrationUrl,
            LocalDate admissionTicketStartsOn,
            LocalDate admissionTicketEndsOn,
            LocalDate writtenExamOn,
            List<String> writtenExamSubjects,
            String graduateRule,
            String overseasDegreeRule,
            String experienceEvidenceRule,
            String employmentStatement,
            String interviewRule,
            Map<String, String> evidenceExcerpts
        ) {
            this(publishedOn, applicationStartsAt, applicationEndsAt, qualificationReviewEndsOn, paymentEndsOn,
                ageReferenceDate, registrationUrl, admissionTicketStartsOn, admissionTicketEndsOn, writtenExamOn,
                writtenExamSubjects, graduateRule, overseasDegreeRule, experienceEvidenceRule, employmentStatement,
                interviewRule, evidenceExcerpts, null, EvidenceState.UNKNOWN, EvidenceState.UNKNOWN,
                EvidenceState.UNKNOWN, null, null, null, null);
        }

        public OfficialAnnouncementFacts {
            writtenExamSubjects = List.copyOf(writtenExamSubjects);
            evidenceExcerpts = Map.copyOf(evidenceExcerpts);
            writtenExamState = writtenExamState == null ? EvidenceState.UNKNOWN : writtenExamState;
            professionalTestState = professionalTestState == null ? EvidenceState.UNKNOWN : professionalTestState;
            interviewState = interviewState == null ? EvidenceState.UNKNOWN : interviewState;
            processFacts = processFacts == null
                ? RecruitmentProcessFacts.fromLegacy(writtenExamState, professionalTestState, interviewState)
                : processFacts;
        }
    }
}
