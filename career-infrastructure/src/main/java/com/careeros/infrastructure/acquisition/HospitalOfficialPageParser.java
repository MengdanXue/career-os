package com.careeros.infrastructure.acquisition;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public final class HospitalOfficialPageParser {
    private static final Pattern DATE = Pattern.compile("(20\\d{2})[-年/.](\\d{1,2})[-月/.](\\d{1,2})");
    private static final String APPLICATION_HOST = "zhaopin.hz-hospital.com";
    private static final Map<String, List<String>> REQUIRED = Map.of(
        "department", List.of("科室部门", "科室", "部门", "科室岗位"),
        "title", List.of("岗位名称", "招聘岗位", "岗位"),
        "category", List.of("岗位类别", "岗位类型", "类别"),
        "education", List.of("学历学位", "学历及学位", "学历", "学历要求"),
        "majors", List.of("专业要求", "所学专业", "专业"),
        "scope", List.of("招聘对象", "人员范围", "对象范围"),
        "headcount", List.of("招聘人数", "需求人数", "人数", "计划数"),
        "age", List.of("年龄要求", "年龄条件", "年龄")
    );
    private static final Map<String, List<String>> OPTIONAL = Map.of(
        "actualEmployer", List.of("实际用人单位", "用人单位", "招聘单位", "所属单位"),
        "worksite", List.of("工作地点", "工作院区", "院区", "院区地点"),
        "employment", List.of("用工性质", "编制性质", "岗位性质", "聘用形式")
    );

    public ParsedHospitalAnnouncement parse(URI sourceUri, byte[] html) {
        Objects.requireNonNull(sourceUri, "sourceUri");
        Objects.requireNonNull(html, "html");
        Document document = Jsoup.parse(new String(html, StandardCharsets.UTF_8), sourceUri.toString());
        String title = firstText(document.selectFirst("h1"), document.selectFirst("title"));
        if (title == null) title = "杭州市第一人民医院招聘公告";
        LocalDate publishedOn = publishedOn(document);
        String applicationUrl = applicationUrl(document);
        TableParse table = parseJobTable(document);
        return new ParsedHospitalAnnouncement(title, publishedOn, applicationUrl,
            table.jobs(), table.issues());
    }

    public boolean supports(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
        return "zp.hz-hospital.com".equalsIgnoreCase(uri.getHost())
            && uri.getPath() != null && uri.getPath().contains("/announcement_desc/");
    }

    private static TableParse parseJobTable(Document document) {
        for (Element table : document.select("table")) {
            Header header = header(table);
            if (header == null) continue;
            List<HospitalJobRow> result = new ArrayList<>();
            List<HospitalParseIssue> issues = new ArrayList<>();
            List<Element> rows = table.select("tr");
            for (int index = header.rowIndex() + 1; index < rows.size(); index++) {
                List<Element> cells = rows.get(index).select("th,td");
                if (cells.isEmpty()) continue;
                String title = value(cells, header.columns().get("title"));
                if (title == null) {
                    issues.add(new HospitalParseIssue(index + 1, "MISSING_JOB_TITLE", "岗位名称为空"));
                    continue;
                }
                if (title.equals("岗位名称")) continue;
                result.add(new HospitalJobRow(
                    value(cells, header.columns().get("department")), title,
                    value(cells, header.columns().get("category")),
                    value(cells, header.columns().get("education")),
                    value(cells, header.columns().get("majors")),
                    value(cells, header.columns().get("scope")),
                    value(cells, header.columns().get("headcount")),
                    value(cells, header.columns().get("age")),
                    value(cells, header.columns().get("actualEmployer")),
                    value(cells, header.columns().get("worksite")),
                    value(cells, header.columns().get("employment"))));
            }
            return new TableParse(List.copyOf(result), List.copyOf(issues));
        }
        return new TableParse(List.of(), document.select("table").isEmpty() ? List.of()
            : List.of(new HospitalParseIssue(1, "JOB_TABLE_HEADER_UNRECOGNIZED", "未识别到完整岗位表头")));
    }

    private static Header header(Element table) {
        List<Element> rows = table.select("tr");
        for (int rowIndex = 0; rowIndex < Math.min(rows.size(), 8); rowIndex++) {
            List<Element> cells = rows.get(rowIndex).select("th,td");
            Map<String, Integer> columns = new LinkedHashMap<>();
            for (int column = 0; column < cells.size(); column++) {
                String label = normalize(cells.get(column).text());
                for (var required : REQUIRED.entrySet()) {
                    if (!columns.containsKey(required.getKey())
                        && required.getValue().stream().anyMatch(label::equals)) {
                        columns.put(required.getKey(), column);
                    }
                }
                for (var optional : OPTIONAL.entrySet()) {
                    if (!columns.containsKey(optional.getKey())
                        && optional.getValue().stream().anyMatch(label::equals)) {
                        columns.put(optional.getKey(), column);
                    }
                }
            }
            if (columns.keySet().containsAll(REQUIRED.keySet())) return new Header(rowIndex, columns);
        }
        return null;
    }

    private static LocalDate publishedOn(Document document) {
        for (String selector : List.of(
            "meta[name=PubDate]", "meta[name=publishdate]", "meta[name=PublishTime]")) {
            Element meta = document.selectFirst(selector);
            if (meta != null) {
                LocalDate parsed = date(meta.attr("content"));
                if (parsed != null) return parsed;
            }
        }
        return date(document.text());
    }

    private static LocalDate date(String value) {
        if (value == null) return null;
        Matcher matcher = DATE.matcher(value);
        if (!matcher.find()) return null;
        try {
            return LocalDate.parse(matcher.group(1) + "-" + pad(matcher.group(2)) + "-" + pad(matcher.group(3)),
                DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String applicationUrl(Document document) {
        for (Element link : document.select("a[href]")) {
            try {
                URI uri = URI.create(link.attr("abs:href"));
                if (APPLICATION_HOST.equalsIgnoreCase(uri.getHost())) return uri.toString();
            } catch (RuntimeException ignored) {
                // Invalid evidence links are ignored and never handed to the fetcher.
            }
        }
        Matcher matcher = Pattern.compile("http://zhaopin\\.hz-hospital\\.com:8080/?").matcher(document.text());
        return matcher.find() ? matcher.group() : null;
    }

    private static String firstText(Element... elements) {
        for (Element element : elements) {
            if (element != null && !element.text().isBlank()) return element.text().trim();
        }
        return null;
    }

    private static String value(List<Element> cells, Integer index) {
        if (index == null || index < 0 || index >= cells.size()) return null;
        String value = cells.get(index).text().replace('\u00a0', ' ').trim();
        return value.isBlank() ? null : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\s\\n\\r：:（）()/]", "").trim();
    }

    private static String pad(String value) { return value.length() == 1 ? "0" + value : value; }

    private record Header(int rowIndex, Map<String, Integer> columns) {}

    private record TableParse(List<HospitalJobRow> jobs, List<HospitalParseIssue> issues) {}

    public record ParsedHospitalAnnouncement(
        String title,
        LocalDate publishedOn,
        String applicationUrl,
        List<HospitalJobRow> jobs,
        List<HospitalParseIssue> issues
    ) {
        public ParsedHospitalAnnouncement(
            String title, LocalDate publishedOn, String applicationUrl, List<HospitalJobRow> jobs
        ) {
            this(title, publishedOn, applicationUrl, jobs, List.of());
        }

        public ParsedHospitalAnnouncement {
            if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
            title = title.trim();
            applicationUrl = applicationUrl == null || applicationUrl.isBlank() ? null : applicationUrl.trim();
            jobs = jobs == null ? List.of() : List.copyOf(jobs);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }

        public boolean completeSnapshot() { return issues.isEmpty(); }
    }

    public record HospitalParseIssue(int rowNumber, String errorCode, String message) {
        public HospitalParseIssue {
            if (rowNumber < 1) throw new IllegalArgumentException("rowNumber must be positive");
            errorCode = requireText(errorCode, "errorCode");
            message = requireText(message, "message");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    public record HospitalJobRow(
        String department,
        String title,
        String category,
        String educationDegree,
        String majors,
        String candidateScope,
        String headcount,
        String ageLimit,
        String actualEmployer,
        String worksite,
        String employmentText
    ) {
        public HospitalJobRow(
            String department, String title, String category, String educationDegree,
            String majors, String candidateScope, String headcount, String ageLimit
        ) {
            this(department, title, category, educationDegree, majors, candidateScope,
                headcount, ageLimit, null, null, null);
        }

        public HospitalJobRow {
            title = required(title, "title");
            department = optional(department);
            category = optional(category);
            educationDegree = optional(educationDegree);
            majors = optional(majors);
            candidateScope = optional(candidateScope);
            headcount = optional(headcount);
            ageLimit = optional(ageLimit);
            actualEmployer = optional(actualEmployer);
            worksite = optional(worksite);
            employmentText = optional(employmentText);
        }

        private static String required(String value, String field) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
            return value.trim();
        }

        private static String optional(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
