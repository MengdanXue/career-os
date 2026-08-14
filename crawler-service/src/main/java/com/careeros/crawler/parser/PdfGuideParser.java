package com.careeros.crawler.parser;

import com.careeros.crawler.domain.RecruitmentRuleDocument;
import com.careeros.crawler.domain.RecruitmentRuleDocument.Extraction;
import com.careeros.crawler.domain.RecruitmentRuleDocument.RuleItem;
import com.careeros.crawler.domain.RecruitmentRuleDocument.Section;
import com.careeros.crawler.domain.RecruitmentRuleDocument.Source;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PdfGuideParser {
    public static final String TITLE = "浙江省省属事业单位2025年上半年集中公开招聘应聘指南";
    public static final String ANNOUNCEMENT_URL = "https://rlsbt.zj.gov.cn/";
    public static final String ATTACHMENT_URL = "https://career.zjnu.edu.cn/attachment/zjnu/ueditor/file/20250323/4681_%E9%99%84%E4%BB%B62%EF%BC%9A%E5%BA%94%E8%81%98%E6%8C%87%E5%8D%97.pdf";

    private static final Pattern HEADING = Pattern.compile(
            "(?m)^\\s*([一二三四五六七八九十]+)、\\s*(关于[^\\n]+|其他有关问题)\\s*$"
    );
    private static final Pattern QUESTION = Pattern.compile("(?m)^\\s*(\\d+)\\.\\s*");
    private static final Map<String, String> SECTION_TAGS = Map.ofEntries(
            Map.entry("招聘信息查询", "information_query"),
            Map.entry("招聘岗位及待遇", "position_and_benefits"),
            Map.entry("应聘基本条件", "basic_eligibility"),
            Map.entry("招聘对象类别", "applicant_category"),
            Map.entry("年龄条件", "age"),
            Map.entry("学历（学位）条件", "education_and_degree"),
            Map.entry("专业条件", "major"),
            Map.entry("其他条件", "other_requirement"),
            Map.entry("报名", "registration"),
            Map.entry("考试考核", "examination"),
            Map.entry("体检", "medical_exam"),
            Map.entry("考察", "background_check"),
            Map.entry("资格审查", "qualification_review"),
            Map.entry("递补", "replacement"),
            Map.entry("其他有关问题", "other")
    );

    public RecruitmentRuleDocument parse(Path file) throws IOException {
        String fileHash = sha256(file);
        List<PageText> pages = extractPages(file);
        StringBuilder full = new StringBuilder();
        List<PageSpan> pageSpans = new ArrayList<>();
        for (PageText page : pages) {
            int start = full.length();
            full.append(page.text()).append('\n');
            pageSpans.add(new PageSpan(page.page(), start, full.length()));
        }
        String text = full.toString();
        List<HeadingMarker> headings = headings(text);
        List<QuestionMarker> questions = questions(text);
        validateStructure(headings, questions);

        List<MutableSection> sections = headings.stream()
                .map(marker -> new MutableSection(marker, new ArrayList<>())).toList();
        for (int index = 0; index < questions.size(); index++) {
            QuestionMarker marker = questions.get(index);
            HeadingMarker section = lastHeadingBefore(headings, marker.start());
            int nextQuestion = index + 1 < questions.size() ? questions.get(index + 1).start() : text.length();
            int nextHeading = nextHeadingAfter(headings, marker.start());
            int itemEnd = Math.min(nextQuestion, nextHeading);
            int questionEnd = text.indexOf('？', marker.contentStart());
            if (questionEnd < 0 || questionEnd >= itemEnd) {
                throw new IllegalArgumentException("问题缺少问号或跨越下一结构标记: " + marker.number());
            }
            String question = collapse(text.substring(marker.contentStart(), questionEnd + 1));
            String answer = collapse(text.substring(questionEnd + 1, itemEnd));
            if (answer.isBlank()) throw new IllegalArgumentException("问题缺少答案: " + marker.number());
            int startPage = pageForOffset(pageSpans, marker.start());
            int endPage = pageForOffset(pageSpans, Math.max(questionEnd + 1, itemEnd - 1));
            String ruleId = "rule_" + sha256Text(fileHash + "|" + marker.number() + "|" + question).substring(0, 16);
            RuleItem item = new RuleItem(
                    ruleId, marker.number(), question, answer, startPage, endPage,
                    tags(section.title(), question), question + " " + answer
            );
            sections.stream().filter(candidate -> candidate.marker().equals(section)).findFirst().orElseThrow().rules().add(item);
        }

        List<Section> immutableSections = new ArrayList<>();
        for (MutableSection section : sections) {
            List<RuleItem> rules = List.copyOf(section.rules());
            int startPage = rules.get(0).startPage();
            int endPage = rules.get(rules.size() - 1).endPage();
            immutableSections.add(new Section(
                    section.marker().number(), section.marker().title(), startPage, endPage, rules
            ));
        }
        Source source = new Source(
                "S08", TITLE, ANNOUNCEMENT_URL, ATTACHMENT_URL, OffsetDateTime.now(ZoneOffset.UTC).toString(),
                file.getFileName().toString(), fileHash, pages.size(), true
        );
        Extraction extraction = new Extraction(
                "apache-pdfbox", "1.0.0", questions.size(), "machine_checked",
                List.of("本结果是应聘规则问答，不应写入岗位表。", "版式已通过 20 页渲染缩略图人工核验。")
        );
        String documentId = "rule_doc_" + sha256Text(fileHash + "|" + TITLE).substring(0, 16);
        return new RecruitmentRuleDocument("1.0.0", documentId, source, List.copyOf(immutableSections), extraction);
    }

    private static List<PageText> extractPages(Path file) throws IOException {
        List<PageText> result = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = normalizeLines(stripper.getText(document));
                if (text.length() < 100) throw new IllegalArgumentException("PDF 第 " + page + " 页文本过少，需要 OCR");
                result.add(new PageText(page, text));
            }
        }
        return List.copyOf(result);
    }

    private static List<HeadingMarker> headings(String text) {
        List<HeadingMarker> result = new ArrayList<>();
        Matcher matcher = HEADING.matcher(text);
        while (matcher.find()) result.add(new HeadingMarker(matcher.group(1), matcher.group(2).trim(), matcher.start()));
        return List.copyOf(result);
    }

    private static List<QuestionMarker> questions(String text) {
        List<QuestionMarker> result = new ArrayList<>();
        Matcher matcher = QUESTION.matcher(text);
        while (matcher.find()) result.add(new QuestionMarker(Integer.parseInt(matcher.group(1)), matcher.start(), matcher.end()));
        return List.copyOf(result);
    }

    private static void validateStructure(List<HeadingMarker> headings, List<QuestionMarker> questions) {
        if (headings.size() != 15) throw new IllegalArgumentException("预期 15 个章节，实际 " + headings.size());
        if (questions.size() != 79) throw new IllegalArgumentException("预期 79 个问题，实际 " + questions.size());
        for (int index = 0; index < questions.size(); index++) {
            if (questions.get(index).number() != index + 1) {
                throw new IllegalArgumentException("问题编号不连续，位置 " + (index + 1));
            }
        }
    }

    private static HeadingMarker lastHeadingBefore(List<HeadingMarker> headings, int offset) {
        HeadingMarker result = null;
        for (HeadingMarker heading : headings) {
            if (heading.start() >= offset) break;
            result = heading;
        }
        if (result == null) throw new IllegalArgumentException("问题前没有章节标题");
        return result;
    }

    private static int nextHeadingAfter(List<HeadingMarker> headings, int offset) {
        return headings.stream().filter(item -> item.start() > offset).mapToInt(HeadingMarker::start).min().orElse(Integer.MAX_VALUE);
    }

    private static int pageForOffset(List<PageSpan> spans, int offset) {
        for (PageSpan span : spans) if (offset >= span.start() && offset < span.end()) return span.page();
        return spans.get(spans.size() - 1).page();
    }

    private static List<String> tags(String sectionTitle, String question) {
        Set<String> tags = new LinkedHashSet<>();
        String normalizedSection = sectionTitle.replaceFirst("^关于", "");
        tags.add(SECTION_TAGS.getOrDefault(normalizedSection, "other"));
        String[][] keywords = {
                {"应届毕业生", "fresh_graduate"}, {"年龄", "age"}, {"专业", "major"},
                {"学历", "education"}, {"学位", "degree"}, {"工作经历", "work_experience"},
                {"报名", "registration"}, {"笔试", "written_exam"}, {"面试", "interview"},
                {"体检", "medical_exam"}, {"递补", "replacement"}, {"资格", "qualification"}
        };
        for (String[] keyword : keywords) if (question.contains(keyword[0])) tags.add(keyword[1]);
        return List.copyOf(tags);
    }

    private static String normalizeLines(String value) {
        return value.replace('\u00a0', ' ').replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n").trim();
    }

    private static String collapse(String value) {
        return value.replace('\u000c', ' ').replaceAll("\\s+", " ")
                .replaceAll("(?<=\\p{IsHan}) (?=\\p{IsHan})", "")
                .replaceAll("(?<=\\p{IsHan}) (?=\\d)", "")
                .replaceAll("(?<=\\d) (?=\\p{IsHan})", "")
                .trim();
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = digest();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            return hex(digest.digest());
        }
    }

    private static String sha256Text(String value) {
        return hex(digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format(Locale.ROOT, "%02x", item));
        return result.toString();
    }

    private record PageText(int page, String text) {}
    private record PageSpan(int page, int start, int end) {}
    private record HeadingMarker(String number, String title, int start) {}
    private record QuestionMarker(int number, int start, int contentStart) {}
    private record MutableSection(HeadingMarker marker, List<RuleItem> rules) {}
}
