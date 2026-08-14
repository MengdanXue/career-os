package com.careeros.crawler.parser;

import com.careeros.crawler.domain.NormalizedJob;
import com.careeros.crawler.domain.NormalizedJob.Age;
import com.careeros.crawler.domain.NormalizedJob.Application;
import com.careeros.crawler.domain.NormalizedJob.Classification;
import com.careeros.crawler.domain.NormalizedJob.Contact;
import com.careeros.crawler.domain.NormalizedJob.Employer;
import com.careeros.crawler.domain.NormalizedJob.Extraction;
import com.careeros.crawler.domain.NormalizedJob.Locator;
import com.careeros.crawler.domain.NormalizedJob.Position;
import com.careeros.crawler.domain.NormalizedJob.Requirements;
import com.careeros.crawler.domain.NormalizedJob.Source;
import com.careeros.crawler.service.ItClassifier;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;

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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OfficialSpreadsheetParser {
    private static final int MAX_HEADER_ROW = 12;
    private static final int MAX_HEADER_DEPTH = 3;
    private static final int MAX_COLUMNS = 32;
    private static final Pattern INTEGER = Pattern.compile("\\d+");
    private static final Pattern AGE_INCLUSIVE = Pattern.compile("(\\d+)周岁及以下");
    private static final Pattern AGE_EXCLUSIVE = Pattern.compile("(\\d+)周岁以下");
    private static final Pattern BIRTH_BOUNDARY = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日以后出生");
    private static final Pattern EXPERIENCE = Pattern.compile("(?:具有|从事专业工作)?(\\d+)年及以上|满(\\d+)年");
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Pattern PHONE = Pattern.compile("(?:联系电话|咨询电话|电话)?[:：]?\\s*(0?\\d{2,3}-?\\d{7,8}|1\\d{10})");
    private static final Pattern TEACHER = Pattern.compile("([^\\s：:]+老师)[:：]");

    private final ItClassifier classifier;
    private final DataFormatter formatter = new DataFormatter();

    public OfficialSpreadsheetParser(ItClassifier classifier) {
        this.classifier = classifier;
    }

    public ParseResult parse(Path file, SpreadsheetSourceConfig config) throws IOException {
        String fileHash = sha256(file);
        List<NormalizedJob> jobs = new ArrayList<>();
        List<SheetReport> reports = new ArrayList<>();
        try (InputStream input = Files.newInputStream(file);
             Workbook workbook = WorkbookFactory.create(input)) {
            String parserName = workbook.getClass().getSimpleName().startsWith("HSSF")
                    ? "apache-poi-hssf" : "apache-poi-xssf";
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                Map<Long, String> merged = mergedValues(sheet);
                Header header = findHeader(sheet, merged);
                if (header == null) {
                    reports.add(new SheetReport(sheet.getSheetName(), null, null, 0, "no_job_header"));
                    continue;
                }
                int before = jobs.size();
                for (int rowIndex = header.endRow() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) continue;
                    String title = field(sheet, rowIndex, header, Field.TITLE, merged);
                    if (title.isBlank() || isSummary(title)) continue;
                    jobs.add(toJob(file, fileHash, parserName, config, sheet, rowIndex, header, merged));
                }
                reports.add(new SheetReport(
                        sheet.getSheetName(), header.startRow() + 1, header.endRow() + 1,
                        jobs.size() - before, "parsed"
                ));
            }
        }
        return new ParseResult(List.copyOf(jobs), List.copyOf(reports));
    }

    private NormalizedJob toJob(
            Path file, String fileHash, String parserName, SpreadsheetSourceConfig config,
            Sheet sheet, int rowIndex, Header header, Map<Long, String> merged
    ) {
        String title = scalar(field(sheet, rowIndex, header, Field.TITLE, merged));
        String positionCode = blankToNull(scalar(field(sheet, rowIndex, header, Field.POSITION_CODE, merged)));
        String positionLevel = blankToNull(scalar(field(sheet, rowIndex, header, Field.POSITION_LEVEL, merged)));
        String employerValue = scalar(field(sheet, rowIndex, header, Field.EMPLOYER, merged));
        String employer = employerValue.isBlank() ? config.defaultEmployer() : employerValue;
        String department = blankToNull(scalar(field(sheet, rowIndex, header, Field.DEPARTMENT, merged)));
        String category = blankToNull(scalar(field(sheet, rowIndex, header, Field.CATEGORY, merged)));
        Integer headcount = firstInteger(field(sheet, rowIndex, header, Field.HEADCOUNT, merged));
        String responsibilitiesRaw = field(sheet, rowIndex, header, Field.RESPONSIBILITIES, merged);
        String educationRaw = scalar(field(sheet, rowIndex, header, Field.EDUCATION, merged));
        String degreeRaw = scalar(field(sheet, rowIndex, header, Field.DEGREE, merged));
        String majorRaw = scalar(field(sheet, rowIndex, header, Field.MAJOR, merged));
        String ageRaw = scalar(field(sheet, rowIndex, header, Field.AGE, merged));
        String otherRaw = field(sheet, rowIndex, header, Field.OTHER, merged);
        String household = blankToNull(scalar(field(sheet, rowIndex, header, Field.HOUSEHOLD, merged)));
        String gender = blankToNull(scalar(field(sheet, rowIndex, header, Field.GENDER, merged)));
        String contactRaw = field(sheet, rowIndex, header, Field.CONTACT, merged);
        String applicantType = blankToNull(scalar(field(sheet, rowIndex, header, Field.APPLICANT_TYPE, merged)));

        CombinedEducation combined = combinedEducation(educationRaw, degreeRaw, majorRaw);
        Classification classification = classifier.classify(
                title, responsibilitiesRaw, combined.majorText() + "\n" + otherRaw
        );
        int physicalRow = rowIndex + 1;
        String locatorKey = "spreadsheet:" + sheet.getSheetName() + ":" + physicalRow;
        String jobId = "job_" + sha256Text(
                config.announcementUrl() + "|" + fileHash + "|" + locatorKey + "|" + employer + "|" + title
        ).substring(0, 16);

        List<String> evidenceCells = new ArrayList<>();
        Row sourceRow = sheet.getRow(rowIndex);
        int lastEvidenceColumn = sourceRow == null ? lastMappedColumn(header)
                : Math.max(lastMappedColumn(header), Math.max(sourceRow.getLastCellNum() - 1, 0));
        for (int column = 0; column <= lastEvidenceColumn; column++) {
            String value = value(sheet, rowIndex, column, merged);
            if (!value.isBlank()) evidenceCells.add(value);
        }
        Source source = new Source(
                config.sampleId(), config.announcementTitle(), config.announcementUrl(), config.attachmentUrl(),
                config.publishedAt(), OffsetDateTime.now(ZoneOffset.UTC).toString(), file.getFileName().toString(), fileHash,
                new Locator("spreadsheet", sheet.getSheetName(), null, null, physicalRow, "one_based_including_header_rows"),
                String.join(" | ", evidenceCells)
        );
        Position position = new Position(
                title, positionCode, positionLevel, category, headcount, config.employmentType(), List.of(), textItems(responsibilitiesRaw)
        );
        Requirements requirements = new Requirements(
                applicantType, combined.education(), combined.degree(), blankToNull(combined.majorText()), combined.majors(),
                age(ageRaw), experience(otherRaw), politicalStatus(otherRaw), certifications(otherRaw),
                household, normalizeGender(gender), skills(combined.majorText() + "\n" + responsibilitiesRaw + "\n" + otherRaw),
                textItems(otherRaw)
        );
        List<Contact> contacts = contacts(contactRaw);
        Application application = new Application(null, null, null, null, contacts);
        Extraction extraction = new Extraction(
                parserName, "1.0.0", 0.96, "machine_checked",
                List.of("报名时间和报名方式需从公告正文跨文档补全。")
        );
        return new NormalizedJob(
                "1.1.0", jobId, source, new Employer(employer, department, config.organizationType()),
                position, requirements, application, classification, extraction
        );
    }

    private Header findHeader(Sheet sheet, Map<Long, String> merged) {
        Header best = null;
        int lastCandidate = Math.min(sheet.getLastRowNum(), MAX_HEADER_ROW - 1);
        for (int start = 0; start <= lastCandidate; start++) {
            for (int depth = 1; depth <= MAX_HEADER_DEPTH && start + depth - 1 <= lastCandidate; depth++) {
                int end = start + depth - 1;
                Map<Field, Integer> columns = mapColumns(sheet, start, end, merged);
                if (!columns.containsKey(Field.TITLE) || !columns.containsKey(Field.HEADCOUNT)) continue;
                int score = columns.size() * 100 + (columns.containsKey(Field.EMPLOYER) ? 20 : 0)
                        + (columns.containsKey(Field.MAJOR) ? 20 : 0) - depth;
                if (best == null || score > best.score()) best = new Header(start, end, columns, score);
            }
        }
        return best;
    }

    private Map<Field, Integer> mapColumns(Sheet sheet, int start, int end, Map<Long, String> merged) {
        int maxColumn = 0;
        for (int rowIndex = start; rowIndex <= end; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row != null) maxColumn = Math.max(maxColumn, Math.max(row.getLastCellNum(), 0));
        }
        maxColumn = Math.min(maxColumn, MAX_COLUMNS);
        Map<Field, Integer> result = new EnumMap<>(Field.class);
        Map<Field, Integer> bestScores = new EnumMap<>(Field.class);
        for (int column = 0; column < maxColumn; column++) {
            Set<String> parts = new LinkedHashSet<>();
            for (int rowIndex = start; rowIndex <= end; rowIndex++) {
                String item = value(sheet, rowIndex, column, merged);
                if (!item.isBlank()) parts.add(item);
            }
            String label = String.join(" / ", parts);
            for (Field field : Field.values()) {
                int match = field.matchScore(label, parts);
                if (match > bestScores.getOrDefault(field, -1)) {
                    result.put(field, column);
                    bestScores.put(field, match);
                }
            }
        }
        result.entrySet().removeIf(entry -> bestScores.getOrDefault(entry.getKey(), -1) < 0);
        return result;
    }

    private String field(Sheet sheet, int rowIndex, Header header, Field field, Map<Long, String> merged) {
        Integer column = header.columns().get(field);
        return column == null ? "" : value(sheet, rowIndex, column, merged);
    }

    private String value(Sheet sheet, int rowIndex, int columnIndex, Map<Long, String> merged) {
        Row row = sheet.getRow(rowIndex);
        if (row != null) {
            Cell cell = row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null) {
                String formatted = clean(formatter.formatCellValue(cell));
                if (!formatted.isBlank()) return formatted;
            }
        }
        return merged.getOrDefault(key(rowIndex, columnIndex), "");
    }

    private Map<Long, String> mergedValues(Sheet sheet) {
        Map<Long, String> result = new HashMap<>();
        for (CellRangeAddress range : sheet.getMergedRegions()) {
            Row row = sheet.getRow(range.getFirstRow());
            if (row == null) continue;
            Cell cell = row.getCell(range.getFirstColumn(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell == null) continue;
            String value = clean(formatter.formatCellValue(cell));
            if (value.isBlank()) continue;
            for (int r = range.getFirstRow(); r <= range.getLastRow(); r++) {
                for (int c = range.getFirstColumn(); c <= range.getLastColumn(); c++) result.put(key(r, c), value);
            }
        }
        return result;
    }

    private static long key(int row, int column) {
        return ((long) row << 32) | (column & 0xffffffffL);
    }

    private static int lastMappedColumn(Header header) {
        return header.columns().values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    private static boolean isSummary(String title) {
        String normalized = title.trim();
        return normalized.equals("合计") || normalized.startsWith("备注") || normalized.startsWith("说明");
    }

    private static CombinedEducation combinedEducation(String educationRaw, String degreeRaw, String majorRaw) {
        String education = blankToNull(educationRaw);
        String degree = blankToNull(degreeRaw);
        String major = blankToNull(majorRaw);
        if (education != null && education.equals(degree)) degree = null;
        if (education != null && education.equals(major)) major = null;
        if (education != null && education.contains("/")) {
            String[] pieces = education.split("/", 2);
            education = blankToNull(pieces[0].trim());
            if (degree == null) degree = blankToNull(pieces[1].trim());
        }
        if (education != null && education.contains("，专业")) {
            String[] pieces = education.split("，", 2);
            education = pieces[0].trim();
            if (major == null) major = pieces[1].trim();
        }
        if (major != null && (major.equals("不限") || major.equals("专业不限"))) major = "专业不限";
        List<String> majors = major == null || major.contains("不限") ? List.of()
                : Arrays.stream(major.split("[、,，]"))
                .map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
        return new CombinedEducation(education, degree, major == null ? "" : major, majors);
    }

    private static Age age(String raw) {
        String text = clean(raw);
        if (text.isBlank() || text.equals("——") || text.equals("-")) return null;
        Matcher inclusive = AGE_INCLUSIVE.matcher(text);
        Matcher exclusive = AGE_EXCLUSIVE.matcher(text);
        Matcher boundary = BIRTH_BOUNDARY.matcher(text);
        String birthDate = boundary.find()
                ? String.format(Locale.ROOT, "%s-%02d-%02d", boundary.group(1), Integer.parseInt(boundary.group(2)), Integer.parseInt(boundary.group(3)))
                : null;
        if (inclusive.find()) return new Age("<=", Integer.parseInt(inclusive.group(1)), birthDate, text);
        if (exclusive.find()) return new Age("<", Integer.parseInt(exclusive.group(1)), birthDate, text);
        if (birthDate != null) return new Age("unknown", null, birthDate, text);
        return new Age("unknown", null, null, text);
    }

    private static Double experience(String raw) {
        Matcher matcher = EXPERIENCE.matcher(raw);
        if (!matcher.find()) return null;
        String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return Double.valueOf(value);
    }

    private static String politicalStatus(String raw) {
        if (raw.contains("中共党员（含预备党员）")) return "中共党员（含预备党员）";
        if (raw.contains("中共党员")) return "中共党员";
        return null;
    }

    private static List<String> certifications(String raw) {
        return textItems(raw).stream()
                .filter(item -> item.contains("资格") || item.contains("证书") || item.contains("驾驶证"))
                .toList();
    }

    private static List<String> skills(String raw) {
        String[][] vocabulary = {
                {"计算机", "计算机"}, {"软件工程", "软件工程"}, {"网络安全", "网络安全"},
                {"人工智能", "人工智能"}, {"大数据", "大数据"}, {"数据库", "数据库"},
                {"编程", "编程"}, {"信息化", "信息化"}, {"办公软件", "办公软件"},
                {"数据分析", "数据分析"}, {"沟通协调", "沟通协调"}, {"文字表达", "文字表达"}
        };
        Set<String> result = new LinkedHashSet<>();
        for (String[] item : vocabulary) if (raw.contains(item[0])) result.add(item[1]);
        return List.copyOf(result);
    }

    private static List<Contact> contacts(String raw) {
        String text = clean(raw);
        if (text.isBlank()) return List.of();
        String name = group(TEACHER, text);
        String phone = group(PHONE, text);
        String email = group(EMAIL, text);
        if (name == null && phone == null && email == null) return List.of();
        return List.of(new Contact(name, phone, email));
    }

    private static String normalizeGender(String value) {
        if (value == null || value.isBlank() || value.equals("不限制") || value.equals("不限")) return null;
        return value;
    }

    private static List<String> textItems(String raw) {
        String text = clean(raw);
        if (text.isBlank()) return List.of();
        String[] parts = text.split("(?=\\d+[.、)）])|[；;]");
        Set<String> result = new LinkedHashSet<>();
        for (String part : parts) {
            String item = part.replaceFirst("^\\s*\\d+[.、)）]\\s*", "").trim();
            if (!item.isBlank()) result.add(item);
        }
        return List.copyOf(result);
    }

    private static Integer firstInteger(String value) {
        Matcher matcher = INTEGER.matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group()) : null;
    }

    private static String group(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\u00a0', ' ').replace("\r\n", "\n")
                .replace('\r', '\n').replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n").trim();
    }

    private static String scalar(String value) {
        return clean(value).replaceAll("\\s+", " ").trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || value.equals("-") ? null : value;
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

    private enum Field {
        TITLE("岗位名称", "招聘岗位", "岗位"),
        POSITION_CODE("岗位编号", "岗位序号"),
        POSITION_LEVEL("岗位等级"),
        CATEGORY("岗位类别", "岗位分类", "岗位性质"),
        HEADCOUNT("招聘人数", "招聘 人数", "人数"),
        EMPLOYER("招聘单位", "企业"),
        DEPARTMENT("所在部门", "设岗单位", "部门"),
        APPLICANT_TYPE("招聘对象"),
        RESPONSIBILITIES("岗位主要职责", "岗位描述", "岗位职责"),
        AGE("年龄要求", "年龄条件", "年龄"),
        MAJOR("专业要求", "专业条件", "专业"),
        EDUCATION("学历、学位及专业条件", "学历/学位", "学历"),
        DEGREE("学位"),
        OTHER("其他要求", "其他条件", "具体要求", "岗位要求", "其他"),
        HOUSEHOLD("招聘范围（户籍）", "户籍范围"),
        GENDER("性别要求"),
        CONTACT("考试咨询电话", "咨询电话", "联系方式", "联系人电话及简历投递邮箱");

        private final List<String> aliases;

        Field(String... aliases) {
            this.aliases = List.of(aliases);
        }

        int matchScore(String label, Set<String> parts) {
            int best = -1;
            for (String alias : aliases) {
                if (label.contains(alias)) {
                    int score = alias.length() * 10 + (parts.contains(alias) ? 500 : 0);
                    best = Math.max(best, score);
                }
            }
            return best;
        }
    }

    private record Header(int startRow, int endRow, Map<Field, Integer> columns, int score) {}
    private record CombinedEducation(String education, String degree, String majorText, List<String> majors) {}
    public record SheetReport(String sheet, Integer headerStartRow, Integer headerEndRow, int parsedJobs, String status) {}
    public record ParseResult(List<NormalizedJob> jobs, List<SheetReport> sheets) {}
}
