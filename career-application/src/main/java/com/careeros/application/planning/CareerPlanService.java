package com.careeros.application.planning;

import static com.careeros.application.planning.CareerPlan.*;
import static com.careeros.application.planning.CareerPlanPorts.*;
import static com.careeros.domain.DomainEnums.*;

import com.careeros.domain.CandidateEmploymentRecord.VerificationStatus;
import com.careeros.domain.CandidateProfile;
import com.careeros.domain.EducationRecord.CompletionStatus;
import com.careeros.domain.EducationRecord.CredentialVerificationStatus;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Month;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class CareerPlanService {
    public static final String ALGORITHM_VERSION = "career-plan-v1";
    private static final Set<EmploymentType> FORMAL_TYPES =
        Set.of(EmploymentType.ESTABLISHMENT, EmploymentType.PUBLIC_INSTITUTION_FORMAL);
    private final CareerPlanQuery query;
    private final Clock clock;

    public CareerPlanService(CareerPlanQuery query) { this(query, Clock.systemUTC()); }
    public CareerPlanService(CareerPlanQuery query, Clock clock) {
        this.query = Objects.requireNonNull(query); this.clock = Objects.requireNonNull(clock);
    }

    public CareerPlan generate(UUID candidateId, int targetYear, LocalDate asOf) {
        Objects.requireNonNull(candidateId); Objects.requireNonNull(asOf);
        CareerPlanData data = query.load(candidateId, 2024, 2026, asOf);
        CandidateProfile candidate = data.candidate();
        List<HistoricalJob> jobs = uniqueJobs(data.jobs());
        Scenario current = currentScenario(candidate, asOf);
        List<Scenario> future = futureScenarios(current, candidate, targetYear);
        DataCoverage coverage = coverage(data);
        return new CareerPlan(
            candidateId, targetYear, asOf, snapshot(candidate), current, future,
            routes(jobs, current, future), ageWindows(candidate), recruitmentWindows(jobs), examPatterns(jobs),
            annualSummary(jobs, data.coverage()), risks(candidate, coverage), actions(targetYear, asOf), coverage,
            clock.instant(), ALGORITHM_VERSION);
    }

    private static CandidateSnapshot snapshot(CandidateProfile candidate) {
        var expectedMaster = candidate.educationRecords().stream()
            .filter(record -> record.educationLevel() == EducationLevel.MASTER && record.completionStatus() == CompletionStatus.EXPECTED)
            .map(record -> record.graduationYear()).filter(Objects::nonNull).min(Integer::compareTo).orElse(null);
        String education = expectedMaster == null ? "已完成学历：" + educationLabel(candidate.highestEducation())
            : "本科已完成，硕士预计 " + expectedMaster + " 毕业";
        return new CandidateSnapshot(candidate.displayName(), candidate.birthDate().exactDate().orElse(null),
            candidate.gender(), candidate.profileVersion(), education, expectedMaster);
    }

    private static Scenario currentScenario(CandidateProfile candidate, LocalDate asOf) {
        var masters = candidate.educationRecords().stream()
            .filter(record -> record.educationLevel() == EducationLevel.MASTER).toList();
        boolean verified = masters.stream().anyMatch(record -> record.completionStatus() == CompletionStatus.COMPLETED
            && record.credentialVerificationStatus() == CredentialVerificationStatus.VERIFIED);
        if (verified) return scenario("MASTER_VERIFIED", true, asOf);
        boolean completed = masters.stream().anyMatch(record -> record.completionStatus() == CompletionStatus.COMPLETED);
        if (completed) return scenario("DEGREE_PENDING_VERIFICATION", true, asOf);
        return scenario("PRE_GRADUATION", true, asOf);
    }

    private static List<Scenario> futureScenarios(Scenario current, CandidateProfile candidate, int targetYear) {
        LocalDate graduation = candidate.educationRecords().stream()
            .filter(record -> record.educationLevel() == EducationLevel.MASTER)
            .map(record -> LocalDate.of(record.graduationYear() == null ? targetYear : record.graduationYear(),
                record.graduationMonth() == null ? 6 : record.graduationMonth(), 1))
            .min(LocalDate::compareTo).orElse(LocalDate.of(targetYear, 6, 1));
        var result = new ArrayList<Scenario>();
        if (current.code().equals("PRE_GRADUATION")) result.add(scenario("DEGREE_PENDING_VERIFICATION", false, graduation));
        if (!current.code().equals("MASTER_VERIFIED")) result.add(scenario("MASTER_VERIFIED", false, graduation.plusMonths(1)));
        return List.copyOf(result);
    }

    private static Scenario scenario(String code, boolean current, LocalDate effectiveFrom) {
        return switch (code) {
            case "PRE_GRADUATION" -> new Scenario(code, "本科阶段", "本科已完成；境外硕士尚未取得，硕士硬门槛需按公告期限判断。", effectiveFrom, current);
            case "DEGREE_PENDING_VERIFICATION" -> new Scenario(code, "硕士待认证", "境外硕士证书已取得，但留服认证尚未完成。", effectiveFrom, current);
            case "MASTER_VERIFIED" -> new Scenario(code, "硕士已认证", "硕士与留服认证均完成，可进入硕士岗位的确定性资格判断。", effectiveFrom, current);
            default -> throw new IllegalArgumentException("Unknown scenario: " + code);
        };
    }

    private static List<Route> routes(List<HistoricalJob> jobs, Scenario current, List<Scenario> future) {
        var scenarios = new ArrayList<String>(); scenarios.add(current.code()); future.forEach(value -> scenarios.add(value.code()));
        var routes = List.of(
            route("PUBLIC_TECH", "事业单位信息技术岗", jobs, CareerPlanService::isPublicTech, 82, scenarios),
            route("UNIVERSITY_HOSPITAL_IT", "高校与医院信息化岗", jobs, job -> job.organizationType() == OrganizationType.UNIVERSITY || job.organizationType() == OrganizationType.HOSPITAL, 68, scenarios),
            route("RESEARCH_SUPPORT", "科研与技术支撑岗", jobs, job -> job.organizationType() == OrganizationType.RESEARCH_INSTITUTE || job.jobFamily() == JobFamily.RESEARCH, 58, scenarios),
            route("GOVERNMENT_SOE_DIGITAL", "政府国企数字化岗", jobs, job -> job.organizationType() == OrganizationType.STATE_OWNED_ENTERPRISE || job.organizationType() == OrganizationType.GOVERNMENT, 52, scenarios)
        );
        return routes.stream().sorted(Comparator.comparingInt(Route::priorityScore).reversed().thenComparing(Route::code)).toList();
    }

    private static boolean isPublicTech(HistoricalJob job) {
        return job.organizationType() == OrganizationType.PUBLIC_INSTITUTION;
    }

    private static Route route(String code, String label, List<HistoricalJob> all, Predicate<HistoricalJob> predicate,
        int baseScore, List<String> scenarios) {
        List<HistoricalJob> jobs = all.stream().filter(predicate)
            .sorted(Comparator.comparingInt(HistoricalJob::year).reversed().thenComparing(HistoricalJob::jobId)).toList();
        int events = (int) jobs.stream().map(HistoricalJob::eventId).distinct().count();
        int formal = (int) jobs.stream().filter(job -> FORMAL_TYPES.contains(job.employmentType())).count();
        int score = Math.min(100, baseScore + Math.min(8, jobs.size()) + Math.min(5, formal));
        var representatives = jobs.stream().limit(3).map(job -> new RepresentativeJob(job.jobId(), job.organizationName(),
            job.title(), job.year(), job.sourceUrl(), job.evidenceComplete())).toList();
        var orgs = jobs.stream().map(HistoricalJob::organizationName).filter(Objects::nonNull).distinct().sorted().limit(8).toList();
        var families = jobs.stream().map(job -> job.jobFamily().name()).distinct().sorted().toList();
        List<String> advantages = code.equals("PUBLIC_TECH")
            ? List.of("计算机本科与中级职称可复用于部分岗位", "Java、数据库和信息系统经验与技术岗方向一致")
            : List.of("计算机专业可复用于信息化与数字化岗位");
        List<String> routeRisks = jobs.isEmpty() ? List.of("当前历史样本不足，需继续补采官方来源")
            : List.of("每个岗位仍需逐条核验年龄、专业、经历和身份条件");
        return new Route(code, label, score, "决策指数，不是录取概率", jobs.size(), events, formal, orgs,
            families, scenarios, advantages, routeRisks,
            List.of("职业能力倾向测验与综合应用能力", "信息系统、数据库与网络基础", "准备可核验的学历、职称和工作经历材料"),
            representatives, strength(jobs.size(), events));
    }

    private static EvidenceStrength strength(int jobs, int events) {
        if (jobs >= 20 && events >= 5) return EvidenceStrength.STRONG;
        if (events >= 5) return EvidenceStrength.MODERATE;
        if (events >= 3) return EvidenceStrength.LIMITED;
        return EvidenceStrength.INSUFFICIENT;
    }

    private static List<AgeWindow> ageWindows(CandidateProfile candidate) {
        LocalDate birth = candidate.birthDate().exactDate()
            .orElseThrow(() -> new IllegalStateException("Full birth date is required for age windows"));
        var result = new ArrayList<AgeWindow>();
        for (int year = 2027; year <= 2031; year++) {
            LocalDate reference = LocalDate.of(year, Month.MARCH, 31);
            int age = Period.between(birth, reference).getYears();
            for (int maximum : List.of(35, 38, 40)) {
                boolean eligible = age <= maximum;
                result.add(new AgeWindow(year, reference, maximum, age, eligible,
                    year + " 春季" + (eligible ? "仍在 " : "已超过 ") + maximum + " 周岁窗口" + (eligible ? "内" : ""),
                    "按 1992-12-31 与春季参考日精确计算", true));
            }
        }
        return List.copyOf(result);
    }

    private static List<RecruitmentWindow> recruitmentWindows(List<HistoricalJob> jobs) {
        Map<UUID, HistoricalJob> events = uniqueEvents(jobs);
        Map<Integer, Long> months = events.values().stream().map(CareerPlanService::signalDate).filter(Objects::nonNull)
            .collect(Collectors.groupingBy(LocalDate::getMonthValue, TreeMap::new, Collectors.counting()));
        return months.entrySet().stream().map(entry -> new RecruitmentWindow(entry.getKey(), entry.getValue().intValue(),
            entry.getKey() + " 月预计关注窗口", "来自历史独立招聘事件月份，不是未来截止日期")).toList();
    }

    private static LocalDate signalDate(HistoricalJob job) {
        return job.publishedOn() != null ? job.publishedOn() : job.applicationStartsOn();
    }

    private static List<ExamPattern> examPatterns(List<HistoricalJob> jobs) {
        Map<UUID, HistoricalJob> events = uniqueEvents(jobs);
        Map<String, Long> subjects = events.values().stream().flatMap(job -> job.writtenExamSubjects().stream())
            .filter(value -> value != null && !value.isBlank())
            .collect(Collectors.groupingBy(value -> value, Collectors.counting()));
        return subjects.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .map(entry -> new ExamPattern(entry.getKey(), entry.getValue().intValue())).toList();
    }

    private static List<AnnualSummary> annualSummary(List<HistoricalJob> jobs, List<CoverageSignal> coverage) {
        var result = new ArrayList<AnnualSummary>();
        for (int year = 2024; year <= 2026; year++) {
            int value = year;
            var yearJobs = jobs.stream().filter(job -> job.year() == value).toList();
            int events = (int) yearJobs.stream().map(HistoricalJob::eventId).distinct().count();
            int formal = (int) yearJobs.stream().filter(job -> FORMAL_TYPES.contains(job.employmentType())).count();
            var yearCoverage = coverage.stream().filter(signal -> signal.year() == value).toList();
            boolean complete = !yearCoverage.isEmpty() && yearCoverage.stream().allMatch(CareerPlanService::complete);
            result.add(new AnnualSummary(year, yearJobs.size(), events, formal, complete));
        }
        return List.copyOf(result);
    }

    private static DataCoverage coverage(CareerPlanData data) {
        int complete = (int) data.coverage().stream().filter(CareerPlanService::complete).count();
        var incomplete = data.coverage().stream().filter(signal -> !complete(signal))
            .sorted(Comparator.comparingInt(CoverageSignal::year).thenComparing(CoverageSignal::sourceCode))
            .map(signal -> signal.sourceCode() + ":" + signal.year() + "（" + signal.status() + "）").toList();
        boolean allComplete = !data.coverage().isEmpty() && complete == data.coverage().size();
        var warnings = new ArrayList<String>();
        if (!allComplete) warnings.add("数据尚未补齐；缺失来源不得解释为零招聘。");
        if (data.coverage().isEmpty()) warnings.add("尚无来源年度覆盖账本，当前结果仅作有限参考。");
        return new DataCoverage(allComplete, data.coverage().size(), complete, incomplete, warnings, data.loadedAt());
    }

    private static boolean complete(CoverageSignal signal) {
        return signal.status() == CoverageStatus.COMPLETE || signal.status() == CoverageStatus.NO_TARGET_RECORDS;
    }

    private static List<Risk> risks(CandidateProfile candidate, DataCoverage coverage) {
        var result = new ArrayList<Risk>();
        boolean masterVerified = candidate.educationRecords().stream().anyMatch(record ->
            record.educationLevel() == EducationLevel.MASTER && record.credentialVerificationStatus() == CredentialVerificationStatus.VERIFIED);
        if (!masterVerified) result.add(new Risk("CREDENTIAL_VERIFICATION", RiskSeverity.HIGH, "境外硕士与留服认证",
            "2027 取得学位后尽快提交留服认证；认证完成前不要把硕士门槛视为已满足。"));
        if (candidate.politicalAffiliation() == PoliticalAffiliation.UNKNOWN) result.add(new Risk("POLITICAL_AFFILIATION",
            RiskSeverity.MEDIUM, "政治面貌尚未确认", "党员限定岗位只能标为待确认，不能默认符合。"));
        boolean verifiedEmployment = candidate.employmentRecords().stream()
            .anyMatch(record -> record.verificationStatus() == VerificationStatus.VERIFIED);
        if (!verifiedEmployment) result.add(new Risk("EMPLOYMENT_EVIDENCE", RiskSeverity.HIGH, "工作经历尚未核验",
            "简历时间线不自动计入报考年限；请补充起止日期和可提供的证明类型。"));
        if (!coverage.complete()) result.add(new Risk("SOURCE_COVERAGE", RiskSeverity.MEDIUM, "历史来源覆盖不完整",
            "当前历史数量是已采集切片，不代表完整市场总量。"));
        return List.copyOf(result);
    }

    private static List<ActionItem> actions(int targetYear, LocalDate asOf) {
        return List.of(
            new ActionItem(asOf, LocalDate.of(asOf.getYear(), 12, 31), "核实画像与材料证据", "确认工作起止日期、证明类型、政治面貌和硕士预计毕业月。", "NOW"),
            new ActionItem(LocalDate.of(targetYear, 1, 1), LocalDate.of(targetYear, 3, 31), "监控集中公告并定版报名材料", "重点关注浙江、杭州人社和目标单位官方来源。", "PLANNED"),
            new ActionItem(LocalDate.of(targetYear, 3, 1), LocalDate.of(targetYear, 5, 31), "准备统考与专业基础", "按历史科目复习职测、综应、信息系统、数据库和网络基础。", "PLANNED"),
            new ActionItem(LocalDate.of(targetYear, 6, 1), LocalDate.of(targetYear, 9, 30), "毕业、留服与硕士岗位切换", "取得学位后提交留服认证，并重新计算硕士门槛岗位。", "PLANNED"),
            new ActionItem(LocalDate.of(targetYear + 1, 1, 1), LocalDate.of(targetYear + 1, 5, 31), "进入第二个完整春季周期", "使用上一周期结果调整路线与材料。", "PLANNED")
        );
    }

    private static List<HistoricalJob> uniqueJobs(List<HistoricalJob> jobs) {
        Map<UUID, HistoricalJob> unique = new LinkedHashMap<>();
        jobs.stream().sorted(Comparator.comparing(HistoricalJob::jobId)).forEach(job -> unique.putIfAbsent(job.jobId(), job));
        return List.copyOf(unique.values());
    }

    private static Map<UUID, HistoricalJob> uniqueEvents(List<HistoricalJob> jobs) {
        Map<UUID, HistoricalJob> unique = new LinkedHashMap<>();
        jobs.stream().sorted(Comparator.comparing(HistoricalJob::eventId).thenComparing(HistoricalJob::jobId))
            .forEach(job -> unique.putIfAbsent(job.eventId(), job));
        return unique;
    }

    private static String educationLabel(EducationLevel level) {
        return switch (level) {
            case BACHELOR -> "本科"; case MASTER -> "硕士"; case DOCTORATE -> "博士";
            case ASSOCIATE -> "专科"; case HIGH_SCHOOL -> "高中"; case UNKNOWN -> "未知";
        };
    }

    public static final class CandidateNotFoundException extends RuntimeException {
        public CandidateNotFoundException(String message) { super(message); }
    }
}
