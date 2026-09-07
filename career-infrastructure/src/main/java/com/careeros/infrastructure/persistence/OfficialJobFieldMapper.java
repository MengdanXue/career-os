package com.careeros.infrastructure.persistence;

import com.careeros.application.JobUpsertService.NormalizedJob;
import com.careeros.domain.DomainEnums.EducationLevel;
import com.careeros.domain.DomainEnums.EmploymentType;
import com.careeros.domain.DomainEnums.JobFamily;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class OfficialJobFieldMapper {
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");
    private static final Pattern YEAR = Pattern.compile("(20\\d{2})");
    private static final Pattern DATE = Pattern.compile("(?<!\\d)\\d{4}(?:\\s*年(?:\\s*\\d{1,2}\\s*月(?:\\s*\\d{1,2}\\s*日)?)?|[-/.]\\d{1,2}(?:[-/.]\\d{1,2})?)");
    private static final Pattern AGE = Pattern.compile("(?<!\\d)(\\d{1,3})\\s*(?:周)?岁");
    private static final Pattern AGE_UPPER = Pattern.compile("(?:不超过|不得超过|最高|最大)\\s*(\\d{1,3})\\s*(?:周)?岁|(\\d{1,3})\\s*(?:周)?岁\\s*(?:及)?(?:以下|以内)");
    private static final Pattern AGE_EXCLUSIVE = Pattern.compile("(?:未满|不满)\\s*(\\d{1,3})\\s*(?:周)?岁|(\\d{1,3})\\s*(?:周)?岁\\s*[（(]不含[）)]\\s*以下");
    private static final Pattern AGE_RANGE = Pattern.compile("(?<!\\d)(\\d{1,3})\\s*(?:周岁|岁)?\\s*(?:至|到|[-—~～－])\\s*(\\d{1,3})\\s*(?:周)?岁");
    private static final Pattern CONDITIONAL_AGE = Pattern.compile("博士|硕士|本科|职称|放宽|非|不低于|不少于|不限");
    private static final Pattern EXPERIENCE = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*年(?!制)");
    private static final Pattern EXPERIENCE_CLAUSE = Pattern.compile("经验|经历|年限|[零〇一二三四五六七八九十百两]+年(?!制)");
    private static final Pattern CONDITIONAL_EXPERIENCE = Pattern.compile("不超过|不要求|以内|以下|至多|最多|博士|硕士|本科|应届|优先|不限于");
    private static final Pattern NO_EXPERIENCE = Pattern.compile("^(?:(?:工作)?(?:经历|经验|年限)(?:要求)?[:：]?)?(?:不限|无要求|不作要求|不做要求|无限制)$|^无(?:工作)?(?:经历|经验|年限)要求$");

    public NormalizedJob toNormalizedJob(RawOfficialJob row, ImportContext context) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(context, "context");
        String requirements = join(row.educationText(), row.majorText(), row.ageText(), row.experienceText(), row.candidateScope());
        EmploymentType employmentType = row.employmentText() == null || row.employmentText().isBlank()
            ? context.defaultEmploymentType()
            : employmentType(row.employmentText());
        String actualEmployer = firstPresent(row.actualEmployer(), context.defaultActualEmployer());
        String worksite = firstPresent(row.worksite(), context.defaultWorksite());
        String employmentEvidence = evidence(row, context);
        return new NormalizedJob(
            context.eventId(), context.organizationId(), context.organizationName(),
            optional(row.externalJobCode()), required(row.title(), "title"),
            jobFamily(row.title(), row.duties(), row.majorText(), row.department()),
            employmentType, context.location(),
            Math.max(1, number(row.headcountText(), 1)), education(row.educationText()),
            splitMajors(row.majorText()), graduationYears(row.candidateScope()),
            ageLimit(row.ageText()), context.ageReferenceDate(),
            experienceYears(row.experienceText()), professionalTitles(row.professionalTitleText()),
            optional(row.duties()), context.sourceUrl(), context.stableSourceUrl(), null,
            context.evidenceIds(), row.department(), row.category(), null,
            optional(row.educationText()), optional(row.educationText()), optional(row.majorText()),
            optional(row.ageText()), null, optional(row.candidateScope()), null,
            requirements, null, null, null, actualEmployer, worksite, employmentEvidence);
    }

    static JobFamily jobFamily(String title, String duties, String majors, String department) {
        String position = text(title);
        String role = text(title) + text(duties) + text(department);
        String all = role + text(majors);
        if (role.contains("信息中心") || role.contains("信息管理") || role.contains("信息化")
            || role.contains("信息系统")) return JobFamily.INFORMATION_SYSTEMS;
        if (all.contains("人工智能") || all.contains("算法") || all.contains("机器学习")) return JobFamily.AI;
        if (all.contains("数据")) return JobFamily.DATA;
        if (all.contains("网络安全") || all.contains("信息安全") || all.contains("安全技术")) {
            return JobFamily.CYBERSECURITY;
        }
        if (all.contains("软件") || all.contains("开发") || all.contains("Java")) return JobFamily.SOFTWARE;
        if (all.contains("计算机")) return JobFamily.INFORMATION_SYSTEMS;
        if (all.contains("数字")) return JobFamily.DIGITALIZATION;
        if (all.contains("运维") || all.contains("网络") || all.contains("通信")) return JobFamily.IT_OPERATIONS;
        if (position.contains("研究") || position.contains("科研") || position.contains("研发")
            || position.contains("实验")) return JobFamily.RESEARCH;
        return JobFamily.OTHER;
    }

    static EducationLevel education(String value) {
        String text = text(value);
        if (text.contains("博士")) return EducationLevel.DOCTORATE;
        if (text.contains("硕士") || text.contains("研究生")) return EducationLevel.MASTER;
        if (text.contains("本科")) return EducationLevel.BACHELOR;
        if (text.contains("专科") || text.contains("大专")) return EducationLevel.ASSOCIATE;
        return EducationLevel.UNKNOWN;
    }

    static EmploymentType employmentType(String value) {
        String text = text(value);
        if (text.contains("劳务派遣")) return EmploymentType.LABOR_DISPATCH;
        if (text.contains("人事代理")) return EmploymentType.PERSONNEL_AGENCY;
        if (text.contains("第三方签") || text.contains("与第三方") || text.contains("编外")) {
            return text.contains("项目") ? EmploymentType.PROJECT_BASED : EmploymentType.CONTRACT;
        }
        boolean negatedFormal = text.contains("非正式") || text.contains("不属于") || text.contains("不纳入")
            || text.contains("非事业编") || text.contains("无事业编") || text.contains("不占事业编")
            || text.contains("不进事业编") || text.contains("事业编制外");
        if (!negatedFormal && (text.contains("事业编") || text.contains("编制内"))) {
            return EmploymentType.ESTABLISHMENT;
        }
        if (!negatedFormal && (text.contains("员额") || text.contains("备案制"))) {
            return EmploymentType.QUOTA_OR_FILING;
        }
        if (!negatedFormal && text.contains("国企") && (text.contains("正式") || text.contains("劳动合同"))) {
            return EmploymentType.SOE_FORMAL;
        }
        if (!negatedFormal
            && (text.contains("单位正式聘用") || text.contains("正式聘用人员") || text.contains("正式员工"))) {
            return EmploymentType.UNIT_FORMAL;
        }
        if (text.contains("项目")) return EmploymentType.PROJECT_BASED;
        if (text.contains("合同") || text.contains("编外")) return EmploymentType.CONTRACT;
        return EmploymentType.UNKNOWN;
    }

    static Set<String> splitMajors(String value) {
        if (value == null || value.isBlank()) return Set.of();
        String normalized = value.replaceAll("[\\s，,。；;：:]", "");
        if (List.of("不限", "不限制", "无", "无要求", "专业不限").contains(normalized)) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String part : value.split("[、,，;；/\\n]")) {
            String item = part.trim();
            int restricted = Math.max(item.indexOf("（限"), item.indexOf("(限"));
            if (restricted >= 0) item = item.substring(restricted + 2).trim();
            item = item.replaceFirst("[）)]$", "").replaceFirst("方向$", "").trim();
            if (!item.isBlank()) result.add(item);
        }
        return result;
    }

    static Set<Integer> graduationYears(String value) {
        Set<Integer> result = new LinkedHashSet<>();
        Matcher matcher = YEAR.matcher(text(value));
        while (matcher.find()) result.add(Integer.parseInt(matcher.group(1)));
        return result;
    }

    static Integer ageLimit(String value) {
        String raw = DATE.matcher(text(value)).replaceAll(" ").strip();
        // Conditional relaxations need a rule tied to the applicant's degree/title, not one scalar.
        if (CONDITIONAL_AGE.matcher(raw).find()) return null;
        var range = AGE_RANGE.matcher(raw);
        if (range.matches()) {
            int lower = Integer.parseInt(range.group(1));
            int upper = Integer.parseInt(range.group(2));
            return lower <= upper && plausibleAge(lower) && plausibleAge(upper) ? upper : null;
        }
        var bounds = new LinkedHashSet<Integer>();
        var exclusive = AGE_EXCLUSIVE.matcher(raw);
        while (exclusive.find()) bounds.add(Integer.parseInt(firstPresent(exclusive.group(1), exclusive.group(2))) - 1);
        var upper = AGE_UPPER.matcher(raw);
        while (upper.find()) bounds.add(Integer.parseInt(firstPresent(upper.group(1), upper.group(2))));
        if (bounds.size() == 1) {
            int bound = bounds.iterator().next();
            return plausibleAge(bound) ? bound : null;
        }
        if (!bounds.isEmpty()) return null;
        var bare = AGE.matcher(raw);
        if (bare.matches()) {
            int bound = Integer.parseInt(bare.group(1));
            return plausibleAge(bound) ? bound : null;
        }
        return null;
    }

    static Integer experienceYears(String value) {
        String raw = DATE.matcher(text(value)).replaceAll(" ").strip();
        // An explicit standalone no-experience clause is distinct from missing evidence.
        String[] clauses = raw.split("[，,；;。\\n]");
        var values = new LinkedHashSet<Integer>();
        boolean unrestricted = false;
        boolean otherExperienceClause = false;
        for (String clause : clauses) {
            String part = clause.replaceAll("\\s+", "");
            if (NO_EXPERIENCE.matcher(part).matches()) unrestricted = true;
            else if (EXPERIENCE_CLAUSE.matcher(part).find()) otherExperienceClause = true;
        }
        var matcher = EXPERIENCE.matcher(raw);
        while (matcher.find()) values.add(Integer.parseInt(matcher.group(1)));
        // A second experience clause may contain a condition without an Arabic year count.
        if (unrestricted) return values.isEmpty() && !otherExperienceClause ? 0 : null;
        if (CONDITIONAL_EXPERIENCE.matcher(raw).find()) return null;
        if (values.size() != 1) return null;
        if (!(raw.contains("工作") || raw.contains("经验") || raw.contains("经历")
            || raw.matches("\\d{1,2}\\s*年(?:及以上|以上)?"))) return null;
        int years = values.iterator().next();
        return years >= 0 && years <= 50 ? years : null;
    }

    private static boolean plausibleAge(int age) { return age >= 16 && age <= 70; }

    static Set<String> professionalTitles(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (String level : List.of("正高级", "副高级", "高级", "中级", "初级")) {
            if (text(value).contains(level)) result.add(level);
        }
        return result;
    }

    private static int number(String value, int fallback) {
        Integer parsed = integer(value);
        return parsed == null ? fallback : parsed;
    }

    private static Integer integer(String value) {
        Matcher matcher = NUMBER.matcher(text(value));
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private static String join(String... values) {
        List<String> result = new ArrayList<>();
        for (String value : values) if (value != null && !value.isBlank()) result.add(value.trim());
        return result.isEmpty() ? null : String.join("；", result);
    }

    private static String evidence(RawOfficialJob row, ImportContext context) {
        Map<String, String> fragments = new java.util.LinkedHashMap<>();
        if (context.defaultEmploymentEvidence() != null && !context.defaultEmploymentEvidence().isBlank()) {
            addEvidence(fragments, context.defaultEmploymentEvidence());
        }
        if (row.employmentText() != null && !row.employmentText().isBlank()) {
            addEvidence(fragments, "用工性质：" + row.employmentText().trim());
        }
        if (row.actualEmployer() != null && !row.actualEmployer().isBlank()) {
            addEvidence(fragments, "实际用人单位：" + row.actualEmployer().trim());
        }
        if (row.worksite() != null && !row.worksite().isBlank()) {
            addEvidence(fragments, "工作地点：" + row.worksite().trim());
        }
        if (fragments.isEmpty()) return null;
        return String.join("；", fragments.values());
    }

    private static void addEvidence(Map<String, String> fragments, String value) {
        String raw = value.trim();
        fragments.putIfAbsent(raw.replaceAll("[\\s：:；;]+", ""), raw);
    }

    private static String firstPresent(String value, String fallback) {
        return value == null || value.isBlank() ? optional(fallback) : value.trim();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String text(String value) { return value == null ? "" : value; }

    public record RawOfficialJob(
        String externalJobCode,
        String title,
        String duties,
        String majorText,
        String educationText,
        String employmentText,
        String headcountText,
        String candidateScope,
        String ageText,
        String experienceText,
        String professionalTitleText,
        String department,
        String category,
        String actualEmployer,
        String worksite
    ) {
        public RawOfficialJob(
            String externalJobCode, String title, String duties, String majorText,
            String educationText, String employmentText, String headcountText,
            String candidateScope, String ageText, String experienceText,
            String professionalTitleText, String department, String category
        ) {
            this(externalJobCode, title, duties, majorText, educationText, employmentText,
                headcountText, candidateScope, ageText, experienceText, professionalTitleText,
                department, category, null, null);
        }
    }

    public record ImportContext(
        UUID eventId,
        UUID organizationId,
        String organizationName,
        String location,
        LocalDate ageReferenceDate,
        String sourceUrl,
        String stableSourceUrl,
        List<UUID> evidenceIds,
        EmploymentType defaultEmploymentType,
        String defaultActualEmployer,
        String defaultWorksite,
        String defaultEmploymentEvidence
    ) {
        public ImportContext(
            UUID eventId, UUID organizationId, String organizationName, String location,
            LocalDate ageReferenceDate, String sourceUrl, String stableSourceUrl,
            List<UUID> evidenceIds
        ) {
            this(eventId, organizationId, organizationName, location, ageReferenceDate,
                sourceUrl, stableSourceUrl, evidenceIds, EmploymentType.UNKNOWN, null, null, null);
        }

        public ImportContext {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(organizationId, "organizationId");
            organizationName = required(organizationName, "organizationName");
            sourceUrl = required(sourceUrl, "sourceUrl");
            stableSourceUrl = required(stableSourceUrl, "stableSourceUrl");
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            defaultEmploymentType = defaultEmploymentType == null ? EmploymentType.UNKNOWN : defaultEmploymentType;
        }
    }
}
