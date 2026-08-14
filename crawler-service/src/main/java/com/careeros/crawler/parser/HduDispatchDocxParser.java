package com.careeros.crawler.parser;

import com.careeros.crawler.domain.NormalizedJob;
import com.careeros.crawler.domain.NormalizedJob.Age;
import com.careeros.crawler.domain.NormalizedJob.Application;
import com.careeros.crawler.domain.NormalizedJob.Contact;
import com.careeros.crawler.domain.NormalizedJob.Employer;
import com.careeros.crawler.domain.NormalizedJob.Extraction;
import com.careeros.crawler.domain.NormalizedJob.Locator;
import com.careeros.crawler.domain.NormalizedJob.Position;
import com.careeros.crawler.domain.NormalizedJob.Requirements;
import com.careeros.crawler.domain.NormalizedJob.Source;
import com.careeros.crawler.service.ItClassifier;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HduDispatchDocxParser {
    public static final String ANNOUNCEMENT_TITLE = "杭州电子科技大学公开招聘工作人员（劳务派遣）公告";
    public static final String ANNOUNCEMENT_URL = "https://renshi.hdu.edu.cn/2026/0512/c13762a295245/page.htm";
    public static final String ATTACHMENT_URL = "https://renshi.hdu.edu.cn/_upload/article/files/8a/f3/7af740c841aab31afbc35e9d4466/1d4687a6-fce7-471e-9fd7-8f6622b2eb25.docx";
    public static final String EMPLOYER_NAME = "杭州电子科技大学";

    private static final Pattern INTEGER = Pattern.compile("\\d+");
    private static final Pattern AGE_BELOW = Pattern.compile("年龄(?:在)?(\\d+)岁以下");
    private static final Pattern AGE_NOT_OVER = Pattern.compile("年龄不超过(\\d+)(?:周)?岁");
    private static final Pattern BIRTH_BOUNDARY = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日以后出生");
    private static final Pattern CONTACT_NAME = Pattern.compile("联系人[:：]\\s*([^\\s/]+)");
    private static final Pattern PHONE = Pattern.compile("(?:联系电话|电话)[:：]\\s*([0-9-]+)");
    private static final Pattern EMAIL = Pattern.compile("(?:邮箱)?[:：]?\\s*([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Pattern MAJORS = Pattern.compile("(?:^|\\n)\\s*(?:\\d+[)）])?\\s*([^；;\\n]+?)(?:等)?专业(?:硕士|研究生)");

    private final ItClassifier classifier;

    public HduDispatchDocxParser(ItClassifier classifier) {
        this.classifier = classifier;
    }

    public List<NormalizedJob> parse(Path documentPath) throws IOException {
        String fileHash = sha256(documentPath);
        try (InputStream input = Files.newInputStream(documentPath);
             XWPFDocument document = new XWPFDocument(input)) {
            if (document.getTables().isEmpty()) {
                throw new IllegalArgumentException("DOCX 中未找到岗位表格: " + documentPath);
            }
            XWPFTable table = document.getTables().get(0);
            verifyHeader(table);
            List<NormalizedJob> jobs = new ArrayList<>();
            for (int index = 1; index < table.getNumberOfRows(); index++) {
                XWPFTableRow row = table.getRow(index);
                if (row == null || row.getTableCells().size() < 7) continue;
                List<String> cells = row.getTableCells().stream().map(this::cellText).toList();
                if (cells.get(1).isBlank()) continue;
                jobs.add(toJob(documentPath, fileHash, index + 1, cells));
            }
            return List.copyOf(jobs);
        }
    }

    private NormalizedJob toJob(Path file, String fileHash, int physicalRow, List<String> cells) {
        String title = cells.get(1);
        String category = blankToNull(cells.get(2));
        Integer headcount = firstInteger(cells.get(3));
        String responsibilitiesRaw = cells.get(4);
        String conditions = cells.get(5);
        String contactRaw = cells.get(6);
        List<String> responsibilities = splitNumbered(responsibilitiesRaw);
        List<String> conditionItems = splitNumbered(conditions);
        NormalizedJob.Classification classification = classifier.classify(title, responsibilitiesRaw, conditions);

        String locatorKey = "docx_table:0:" + physicalRow;
        String jobId = "job_" + sha256Text(
                ANNOUNCEMENT_URL + "|" + fileHash + "|" + locatorKey + "|" + EMPLOYER_NAME + "|" + title
        ).substring(0, 16);

        Source source = new Source(
                "S07", ANNOUNCEMENT_TITLE, ANNOUNCEMENT_URL, ATTACHMENT_URL, "2026-05-12",
                OffsetDateTime.now(ZoneOffset.UTC).toString(), file.getFileName().toString(), fileHash,
                new Locator("docx_table", null, 0, null, physicalRow, "one_based_including_header_row"),
                String.join(" | ", cells)
        );
        Employer employer = new Employer(EMPLOYER_NAME, null, "university");
        Position position = new Position(
                title, null, null, category, headcount, "劳务派遣", List.of(), responsibilities
        );
        Requirements requirements = new Requirements(
                null, education(conditions), degree(conditions), majorText(conditions), majors(conditions), age(conditions),
                null, politicalStatus(conditions), List.of(), null, null, skills(conditions), conditionItems
        );
        List<Contact> contacts = contact(contactRaw);
        Application application = new Application(
                null, null, contacts.stream().anyMatch(c -> c.email() != null) ? "电子邮件" : null,
                null, contacts
        );
        Extraction extraction = new Extraction(
                "apache-poi-xwpf", "1.0.0", 0.98, "machine_checked",
                List.of("报名起止时间需从公告正文跨文档补全。")
        );
        return new NormalizedJob(
                "1.1.0", jobId, source, employer, position, requirements, application, classification, extraction
        );
    }

    private void verifyHeader(XWPFTable table) {
        if (table.getNumberOfRows() < 2) throw new IllegalArgumentException("岗位表格没有数据行");
        List<String> header = table.getRow(0).getTableCells().stream().map(this::cellText).toList();
        List<String> required = List.of("序号", "岗位名称", "岗位性质", "招聘人数", "岗位主要职责", "岗位应聘条件");
        for (String name : required) {
            if (header.stream().noneMatch(value -> value.contains(name))) {
                throw new IllegalArgumentException("岗位表格缺少列: " + name);
            }
        }
    }

    private String cellText(XWPFTableCell cell) {
        return clean(cell.getText());
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\u00a0', ' ')
                .replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n")
                .trim();
    }

    static List<String> splitNumbered(String raw) {
        String value = clean(raw);
        if (value.isBlank()) return List.of();
        String[] parts = value.split("(?=\\d+[)）])");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String item = part.replaceFirst("^\\s*\\d+[)）]\\s*", "")
                    .replaceAll("[；;\\s]+$", "").trim();
            if (!item.isBlank()) result.add(item);
        }
        return List.copyOf(result);
    }

    private static Integer firstInteger(String value) {
        Matcher matcher = INTEGER.matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group()) : null;
    }

    private static String education(String text) {
        if (text.contains("硕士研究生及以上学历")) return "硕士研究生及以上";
        if (text.contains("研究生及以上学历")) return "研究生及以上";
        if (text.contains("硕士研究生及以上")) return "硕士研究生及以上";
        if (text.contains("研究生及以上")) return "研究生及以上";
        return null;
    }

    private static String degree(String text) {
        return text.contains("硕士及以上学位") ? "硕士及以上" : null;
    }

    private static String majorText(String text) {
        if (text.contains("专业不限")) return "专业不限";
        Matcher matcher = MAJORS.matcher(text);
        return matcher.find() ? matcher.group(1).trim() + "等专业" : null;
    }

    private static List<String> majors(String text) {
        if (text.contains("专业不限")) return List.of();
        Matcher matcher = MAJORS.matcher(text);
        if (!matcher.find()) return List.of();
        return Arrays.stream(matcher.group(1).split("[、,，]"))
                .map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
    }

    private static Age age(String text) {
        Matcher below = AGE_BELOW.matcher(text);
        Matcher notOver = AGE_NOT_OVER.matcher(text);
        String operator;
        Integer years;
        String raw;
        if (below.find()) {
            operator = "<";
            years = Integer.parseInt(below.group(1));
            raw = below.group();
        } else if (notOver.find()) {
            operator = "<=";
            years = Integer.parseInt(notOver.group(1));
            raw = notOver.group();
        } else {
            return null;
        }
        Matcher boundary = BIRTH_BOUNDARY.matcher(text);
        String date = boundary.find()
                ? String.format(Locale.ROOT, "%s-%02d-%02d", boundary.group(1), Integer.parseInt(boundary.group(2)), Integer.parseInt(boundary.group(3)))
                : null;
        return new Age(operator, years, date, raw);
    }

    private static String politicalStatus(String text) {
        if (text.contains("中共正式党员")) return "中共正式党员";
        if (text.contains("中共党员")) return "中共党员";
        return null;
    }

    private static List<String> skills(String text) {
        String[][] vocabulary = {
                {"Web应用前后端", "Web应用前后端开发"}, {"数据库", "数据库"},
                {"可视化数据分析", "可视化数据分析"}, {"服务器与操作系统", "服务器与操作系统运维"},
                {"人工智能应用开发", "人工智能应用开发"}, {"数据中台", "数据中台运维与接口开发"},
                {"网络安全", "网络安全"}, {"数据安全", "数据安全"},
                {"办公软件", "办公软件"}, {"文字表达", "文字表达"}, {"沟通协调", "沟通协调"}
        };
        Set<String> result = new LinkedHashSet<>();
        for (String[] item : vocabulary) if (text.contains(item[0])) result.add(item[1]);
        return List.copyOf(result);
    }

    private static List<Contact> contact(String text) {
        String name = group(CONTACT_NAME, text);
        String phone = group(PHONE, text);
        String email = group(EMAIL, text);
        if (name == null && phone == null && email == null) return List.of();
        return List.of(new Contact(name, phone, email));
    }

    private static String group(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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
        MessageDigest digest = digest();
        return hex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format(Locale.ROOT, "%02x", item));
        return result.toString();
    }
}
