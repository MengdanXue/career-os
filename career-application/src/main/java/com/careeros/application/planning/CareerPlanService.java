package com.careeros.application.planning;

import static com.careeros.application.planning.CareerPlan.*;
import static com.careeros.application.planning.CareerPlanPorts.*;
import static com.careeros.domain.DomainEnums.*;

import com.careeros.domain.CandidateEmploymentRecord.VerificationStatus;
import com.careeros.domain.CandidateEmploymentRecord.EmploymentMode;
import com.careeros.domain.CandidateEmploymentRecord;
import com.careeros.domain.CandidateProfile;
import com.careeros.domain.CandidateFacts;
import com.careeros.domain.CandidateFacts.CandidateFactKey;
import com.careeros.domain.EducationRecord.CompletionStatus;
import com.careeros.domain.EducationRecord.CredentialVerificationStatus;
import com.careeros.domain.GraduateEligibilityRule.EvidenceState;
import com.careeros.domain.acquisition.TargetSource;
import com.careeros.domain.acquisition.TargetSource.ConnectionStatus;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Month;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class CareerPlanService {
    public static final String ALGORITHM_VERSION = "career-plan-v3";
    private static final Set<EmploymentType> FORMAL_TYPES =
        Set.of(EmploymentType.ESTABLISHMENT, EmploymentType.PUBLIC_INSTITUTION_FORMAL);
    private final CareerPlanQuery query;
    private final Clock clock;
    private static final GraduateEligibilityProjector GRADUATE_PROJECTOR = new GraduateEligibilityProjector();

    public CareerPlanService(CareerPlanQuery query) { this(query, Clock.systemUTC()); }
    public CareerPlanService(CareerPlanQuery query, Clock clock) {
        this.query = Objects.requireNonNull(query); this.clock = Objects.requireNonNull(clock);
    }

    public CareerPlan generate(UUID candidateId, int targetYear, LocalDate asOf) {
        Objects.requireNonNull(candidateId); Objects.requireNonNull(asOf);
        CareerPlanData data = query.load(candidateId, 2024, 2026, asOf);
        CandidateProfile candidate = data.candidate();
        List<HistoricalJob> jobs = uniqueJobs(data.jobs());
        Scenario current = currentScenario(candidate, data.candidateFacts(), asOf);
        List<Scenario> future = futureScenarios(current, candidate, targetYear);
        GraduateTrackSummary graduateTrack = graduateTrack(candidate, data.candidateFacts(), targetYear);
        DataCoverage coverage = coverage(data);
        return new CareerPlan(
            candidateId, targetYear, asOf, snapshot(candidate), current, future, graduateTrack,
            routes(jobs, candidate, data.candidateFacts(), asOf, current, future, targetYear,
                data.coverage(), data.failedSections(), data.targetSources()), ageWindows(candidate, data.candidateFacts()), recruitmentWindows(jobs), examPatterns(jobs),
            examSummary(jobs), processWindows(jobs),
            annualSummary(jobs, data.coverage()), risks(candidate, coverage), actions(targetYear, asOf), coverage,
            configuredCoverage(coverage), targetMarketCoverage(data.targetSources()),
            analysisCoverage(jobs, data.coverage(), data.loadedAt()),
            jobProjections(jobs, candidate, data.candidateFacts(), asOf, current, future, targetYear),
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

    private static Scenario currentScenario(CandidateProfile candidate, CandidateFacts facts, LocalDate asOf) {
        if (!facts.isConfirmed(CandidateFactKey.EDUCATION_RECORDS)) {
            return new Scenario("MASTER_IN_PROGRESS", "境外硕士在读（待确认）",
                "境外硕士在读记录尚未确认，学位与留服认证仍按公告截止时点判断。", asOf, true);
        }
        var masters = candidate.educationRecords().stream()
            .filter(record -> record.educationLevel() == EducationLevel.MASTER).toList();
        boolean verified = masters.stream().anyMatch(record -> record.completionStatus() == CompletionStatus.COMPLETED
            && record.credentialVerificationStatus() == CredentialVerificationStatus.VERIFIED);
        if (verified) return scenario("MASTER_VERIFIED", true, asOf);
        boolean completed = masters.stream().anyMatch(record -> record.completionStatus() == CompletionStatus.COMPLETED);
        if (completed) return scenario("DEGREE_PENDING_VERIFICATION", true, asOf);
        boolean expected = masters.stream().anyMatch(record -> record.completionStatus() == CompletionStatus.EXPECTED);
        return scenario(expected ? "MASTER_IN_PROGRESS" : "PRE_GRADUATION", true, asOf);
    }

    private static List<Scenario> futureScenarios(Scenario current, CandidateProfile candidate, int targetYear) {
        var result = new ArrayList<Scenario>();
        if (current.code().equals("PRE_GRADUATION") || current.code().equals("MASTER_IN_PROGRESS")) {
            result.add(scenario("DEGREE_PENDING_VERIFICATION", false, null));
        }
        if (!current.code().equals("MASTER_VERIFIED")) result.add(scenario("MASTER_VERIFIED", false, null));
        return List.copyOf(result);
    }

    private static Scenario scenario(String code, boolean current, LocalDate effectiveFrom) {
        return switch (code) {
            case "PRE_GRADUATION" -> new Scenario(code, "本科阶段", "本科已完成；境外硕士尚未取得，硕士硬门槛需按公告期限判断。", effectiveFrom, current);
            case "MASTER_IN_PROGRESS" -> new Scenario(code, "境外硕士在读", "本科已完成，境外硕士预计毕业；学位与留服认证须按公告截止时点判断。", effectiveFrom, current);
            case "DEGREE_PENDING_VERIFICATION" -> new Scenario(code, "硕士待认证", "境外硕士证书已取得，但留服认证尚未完成。", effectiveFrom, current);
            case "MASTER_VERIFIED" -> new Scenario(code, "硕士已认证", "硕士与留服认证均完成，可进入硕士岗位的确定性资格判断。", effectiveFrom, current);
            default -> throw new IllegalArgumentException("Unknown scenario: " + code);
        };
    }

    private static GraduateTrackSummary graduateTrack(CandidateProfile candidate, CandidateFacts facts, int targetYear) {
        Integer expected = expectedMasterYear(candidate);
        if (!facts.isConfirmed(CandidateFactKey.EDUCATION_RECORDS) || expected == null) {
            return new GraduateTrackSummary("UNKNOWN", "目标年度应届轨道待确认",
                "需先核实境外硕士预计毕业时间。", QualificationOutcome.UNCERTAIN);
        }
        if (expected == targetYear) {
            return new GraduateTrackSummary("TARGET_YEAR_GRADUATE", targetYear + " 届境外硕士应届生候选",
                "届别上进入目标年度当届通道；最终可报性取决于学位、留服认证和公告截止时点。",
                QualificationOutcome.CONDITIONALLY_ELIGIBLE);
        }
        if (expected < targetYear && expected >= targetYear - 2) {
            return new GraduateTrackSummary("RECENT_GRADUATE_WINDOW", expected + " 届近届毕业生候选",
                "是否可报取决于当年公告是否纳入近两届。", QualificationOutcome.CONDITIONALLY_ELIGIBLE);
        }
        return new GraduateTrackSummary("NOT_IN_GRADUATE_SCOPE", "不在目标年度常规应届窗口",
            "仍可按社会人员通道评估。", QualificationOutcome.INELIGIBLE);
    }

    private static List<Route> routes(List<HistoricalJob> jobs, CandidateProfile candidate, CandidateFacts facts, LocalDate asOf,
        Scenario current, List<Scenario> future, int targetYear, List<CoverageSignal> coverage,
        List<String> failedSections, List<TargetSource> targetSources) {
        var scenarios = new ArrayList<Scenario>(); scenarios.add(current); scenarios.addAll(future);
        var routes = List.of(
            route("PUBLIC_TECH", "事业单位信息技术岗", jobs, CareerPlanService::isPublicTech, candidate, facts, asOf, scenarios, targetYear, coverage, failedSections, targetSources),
            route("UNIVERSITY_HOSPITAL_IT", "高校与医院信息化岗", jobs, job -> job.organizationType() == OrganizationType.UNIVERSITY || job.organizationType() == OrganizationType.HOSPITAL, candidate, facts, asOf, scenarios, targetYear, coverage, failedSections, targetSources),
            route("RESEARCH_SUPPORT", "科研与技术支撑岗", jobs, job -> job.organizationType() == OrganizationType.RESEARCH_INSTITUTE || job.jobFamily() == JobFamily.RESEARCH, candidate, facts, asOf, scenarios, targetYear, coverage, failedSections, targetSources),
            route("GOVERNMENT_SOE_DIGITAL", "政府国企数字化岗", jobs, job -> job.organizationType() == OrganizationType.STATE_OWNED_ENTERPRISE || job.organizationType() == OrganizationType.GOVERNMENT, candidate, facts, asOf, scenarios, targetYear, coverage, failedSections, targetSources)
        );
        return routes.stream().sorted(Comparator
            .comparing(Route::priorityScore, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Route::code)).toList();
    }

    private static boolean isPublicTech(HistoricalJob job) {
        return job.organizationType() == OrganizationType.PUBLIC_INSTITUTION;
    }

    private static Comparator<HistoricalJob> representativeOrder() {
        return Comparator.comparingInt(CareerPlanService::technicalTitleRelevance).reversed()
            .thenComparing(Comparator.comparing(HistoricalJob::evidenceComplete).reversed())
            .thenComparing(Comparator.comparingInt(HistoricalJob::year).reversed())
            .thenComparing(HistoricalJob::jobId);
    }

    private static int technicalTitleRelevance(HistoricalJob job) {
        String title = job.title() == null ? "" : job.title().toLowerCase(Locale.ROOT);
        return Stream.of("信息", "计算机", "软件", "网络", "系统", "数据", "数字", "安全", "开发", "运维", "算法", "智能")
            .anyMatch(title::contains) ? 1 : 0;
    }

    private static Route route(String code, String label, List<HistoricalJob> all, Predicate<HistoricalJob> predicate,
        CandidateProfile candidate, CandidateFacts facts, LocalDate asOf, List<Scenario> scenarios, int targetYear,
        List<CoverageSignal> coverage, List<String> failedSections, List<TargetSource> targetSources) {
        List<HistoricalJob> jobs = all.stream().filter(predicate)
            .sorted(Comparator.comparingInt(HistoricalJob::year).reversed().thenComparing(HistoricalJob::jobId)).toList();
        int events = (int) jobs.stream().map(HistoricalJob::eventId).distinct().count();
        int formal = (int) jobs.stream().filter(job -> FORMAL_TYPES.contains(job.employmentType())).count();
        Map<UUID, List<JobScenarioOutcome>> outcomes = jobs.stream().collect(Collectors.toMap(HistoricalJob::jobId,
            job -> scenarios.stream().map(scenario -> evaluate(job, candidate, facts, asOf, scenario.code())).toList(),
            (left, right) -> left, LinkedHashMap::new));
        Map<UUID, ProjectedJobOutcome> projected = jobs.stream().collect(Collectors.toMap(HistoricalJob::jobId,
            job -> projected(job, candidate, facts, asOf, scenarios.getFirst().code(), targetYear),
            (left, right) -> left, LinkedHashMap::new));
        var breakdowns = scenarios.stream().map(scenario -> breakdown(scenario.code(), outcomes.values())).toList();
        var analogBreakdown = breakdown("TARGET_YEAR_ANALOG", projected.values().stream()
            .map(value -> List.of(value.targetYearAnalog())).toList());
        Set<JobFamily> routeFamilies = jobs.stream().map(HistoricalJob::jobFamily).collect(Collectors.toSet());
        int verifiedYears = facts.isConfirmed(CandidateFactKey.EMPLOYMENT_HISTORY)
            ? verifiedFullTimeYears(candidate, asOf, routeFamilies) : 0;
        boolean employmentConfirmed = facts.isConfirmed(CandidateFactKey.EMPLOYMENT_HISTORY);
        boolean targetFamiliesConfirmed = facts.isConfirmed(CandidateFactKey.TARGET_JOB_FAMILIES);
        var components = scoreComponents(jobs, formal, candidate, verifiedYears, employmentConfirmed,
            targetFamiliesConfirmed, analogBreakdown);
        int score = components.stream().mapToInt(value -> value.score() * value.weight()).sum() / 100;
        RouteRankingState rankingState = rankingState(code, jobs, coverage, failedSections, targetSources);
        Integer rankedScore = rankingState == RouteRankingState.RANKED || rankingState == RouteRankingState.LIMITED
            ? score : null;
        var representatives = jobs.stream().sorted(representativeOrder()).limit(3)
            .map(job -> new RepresentativeJob(job.jobId(), job.organizationName(),
            job.title(), job.year(), job.sourceUrl(), job.evidenceComplete(), outcomes.get(job.jobId()),
            projected.get(job.jobId()).historicalActual(), projected.get(job.jobId()).targetYearAnalog())).toList();
        var orgs = jobs.stream().map(HistoricalJob::organizationName).filter(Objects::nonNull).distinct().sorted().limit(8).toList();
        var families = jobs.stream().map(job -> job.jobFamily().name()).distinct().sorted().toList();
        var advantages = new ArrayList<String>();
        advantages.add(code.equals("PUBLIC_TECH") ? "计算机本科与中级职称可复用于部分岗位" : "计算机专业可复用于信息化与数字化岗位");
        if (verifiedYears > 0) advantages.add(verifiedYears + " 个完整年已核验全职经历可用于部分年限门槛");
        List<String> routeRisks = jobs.isEmpty() ? List.of("当前历史样本不足，需继续补采官方来源")
            : List.of("每个岗位仍需逐条核验年龄、专业、经历和身份条件");
        List<String> applicable = breakdowns.stream()
            .filter(value -> value.eligible() + value.conditionallyEligible() > 0)
            .map(ScenarioBreakdown::scenarioCode).toList();
        return new Route(code, label, rankedScore, "五项证据加权决策指数，不是录取概率", jobs.size(), events, formal, orgs,
            families, applicable, advantages, routeRisks,
            List.of("职业能力倾向测验与综合应用能力", "信息系统、数据库与网络基础", "准备可核验的学历、职称和工作经历材料"),
            representatives, breakdowns, components, strength(jobs, events), rankingState,
            rankingReason(rankingState));
    }

    private static RouteRankingState rankingState(String routeCode, List<HistoricalJob> jobs,
        List<CoverageSignal> coverage, List<String> failedSections, List<TargetSource> targetSources) {
        if (!failedSections.isEmpty()) return RouteRankingState.DATA_FAILURE;
        if (!targetSources.isEmpty()) {
            List<TargetSource> routeTargets = targetSources.stream()
                .filter(source -> source.routeCode().equals(routeCode)).toList();
            if (routeTargets.isEmpty()) return RouteRankingState.NOT_COVERED;
            boolean marketComplete = routeTargets.stream()
                .allMatch(source -> source.connectionStatus() == ConnectionStatus.CONNECTED);
            if (jobs.isEmpty()) {
                boolean completedAbsence = marketComplete && routeTargets.stream().allMatch(source ->
                    coverage.stream().filter(signal -> signal.sourceCode().equals(source.code()))
                        .findAny().filter(CareerPlanService::complete).isPresent());
                return completedAbsence ? RouteRankingState.NO_TARGET_RECORDS : RouteRankingState.NOT_COVERED;
            }
            if (!marketComplete) return RouteRankingState.LIMITED;
        } else if (jobs.isEmpty()) {
            return RouteRankingState.NOT_COVERED;
        }
        boolean complete = !coverage.isEmpty() && coverage.stream()
            .allMatch(signal -> signal.status() == CoverageStatus.COMPLETE
                || signal.status() == CoverageStatus.NO_TARGET_RECORDS);
        return complete ? RouteRankingState.RANKED : RouteRankingState.LIMITED;
    }

    private static String rankingReason(RouteRankingState state) {
        return switch (state) {
            case RANKED -> "目前已连接来源覆盖完成，参与排名";
            case LIMITED -> "已有目标岗位，但市场覆盖仍有缺口，限制性排名";
            case NOT_COVERED -> "尚未接入该路线的目标来源，暂不排名";
            case NO_TARGET_RECORDS -> "目标来源覆盖完成，但未发现符合范围的岗位";
            case DATA_FAILURE -> "历史数据查询失败，暂停排名";
        };
    }

    private static ScenarioBreakdown breakdown(String scenarioCode, java.util.Collection<List<JobScenarioOutcome>> outcomes) {
        var values = outcomes.stream().flatMap(List::stream).filter(value -> value.scenarioCode().equals(scenarioCode)).toList();
        return new ScenarioBreakdown(scenarioCode,
            count(values, QualificationOutcome.ELIGIBLE), count(values, QualificationOutcome.CONDITIONALLY_ELIGIBLE),
            count(values, QualificationOutcome.UNCERTAIN), count(values, QualificationOutcome.INELIGIBLE),
            values.isEmpty() ? List.of("该路线暂无已采集历史岗位，不能据此判断没有机会。") : List.of());
    }

    private static int count(List<JobScenarioOutcome> values, QualificationOutcome outcome) {
        return (int) values.stream().filter(value -> value.outcome() == outcome).count();
    }

    private static List<ScoreComponent> scoreComponents(List<HistoricalJob> jobs, int formal,
        CandidateProfile candidate, int verifiedYears, boolean employmentConfirmed,
        boolean targetFamiliesConfirmed, ScenarioBreakdown current) {
        int total = jobs.size();
        int readiness = total == 0 ? 0 : (100 * current.eligible() + 65 * current.conditionallyEligible()
            + 35 * current.uncertain()) / total;
        int stability = total == 0 ? 0 : formal * 100 / total;
        int supply = Math.min(100, total * 5);
        long matchingFamilies = targetFamiliesConfirmed ? jobs.stream().map(HistoricalJob::jobFamily).distinct()
            .filter(candidate.targetJobFamilies()::contains).count() : 0;
        long distinctFamilies = jobs.stream().map(HistoricalJob::jobFamily).distinct().count();
        int reuse = !targetFamiliesConfirmed || distinctFamilies == 0 ? 0 : (int) (matchingFamilies * 100 / distinctFamilies);
        return List.of(
            new ScoreComponent("ELIGIBILITY_READINESS", "目标年份资格准备度", readiness, 30,
                total == 0 ? "无历史岗位样本，不计分" : String.format("目标年度类比：可报 %d、条件可报 %d、待确认 %d、不可报 %d",
                    current.eligible(), current.conditionallyEligible(), current.uncertain(), current.ineligible()), total > 0),
            new ScoreComponent("EXPERIENCE_ADVANTAGE", "已核验经历优势", Math.min(100, verifiedYears * 20), 25,
                !employmentConfirmed ? "工作经历尚未确认，不计优势分"
                    : verifiedYears == 0 ? "工作经历已确认，但没有可计入该路线的完整相关全职年限"
                    : verifiedYears + " 个完整年已核验、去重且与该路线相关的全职经历",
                employmentConfirmed),
            new ScoreComponent("EMPLOYMENT_STABILITY", "正式用工占比", stability, 20,
                total == 0 ? "无历史岗位样本，不计分" : formal + "/" + total + " 个历史岗位为编制或事业单位正式聘用", total > 0),
            new ScoreComponent("HISTORICAL_SUPPLY", "历史供给强度", supply, 15,
                total == 0 ? "当前采集范围内无样本；不等于没有招聘" : "已去重历史岗位 " + total + " 个；20 个达到本项满分", total > 0),
            new ScoreComponent("PREPARATION_REUSE", "准备内容复用", reuse, 10,
                !targetFamiliesConfirmed ? "目标岗位方向尚未确认，不计复用分"
                    : distinctFamilies == 0 ? "无岗位族样本，不计分"
                    : matchingFamilies + "/" + distinctFamilies + " 个岗位族与已确认目标方向重合",
                targetFamiliesConfirmed && distinctFamilies > 0)
        );
    }

    private static ProjectedJobOutcome projected(HistoricalJob job, CandidateProfile candidate, CandidateFacts facts,
        LocalDate asOf, String scenarioCode, int targetYear) {
        JobScenarioOutcome historicalBase = evaluate(job, candidate, facts, asOf, scenarioCode, false, targetYear);
        JobScenarioOutcome analogBase = evaluate(job, candidate, facts, asOf, scenarioCode, true, targetYear);
        if (job.graduateEligibilityRule() == null) {
            return new ProjectedJobOutcome(
                renamed(historicalBase, "HISTORICAL_ACTUAL"),
                renamed(analogBase, "TARGET_YEAR_ANALOG"));
        }
        var historicalGraduate = GRADUATE_PROJECTOR.assess(candidate, facts, job.graduateEligibilityRule(),
            GraduateEligibilityProjector.EvaluationMode.HISTORICAL_ACTUAL, targetYear);
        var analogGraduate = GRADUATE_PROJECTOR.assess(candidate, facts, job.graduateEligibilityRule(),
            GraduateEligibilityProjector.EvaluationMode.TARGET_YEAR_ANALOG, targetYear);
        return new ProjectedJobOutcome(
            merge(historicalBase, historicalGraduate.outcome(), historicalGraduate.reasons(), "HISTORICAL_ACTUAL"),
            merge(analogBase, analogGraduate.outcome(), analogGraduate.reasons(), "TARGET_YEAR_ANALOG"));
    }

    private static List<JobProjection> jobProjections(List<HistoricalJob> jobs, CandidateProfile candidate,
        CandidateFacts facts, LocalDate asOf, Scenario current, List<Scenario> future, int targetYear) {
        var scenarios = new ArrayList<Scenario>();
        scenarios.add(current);
        scenarios.addAll(future);
        return jobs.stream().sorted(Comparator.comparing(HistoricalJob::jobId)).map(job -> {
            var outcomes = scenarios.stream().map(scenario ->
                evaluate(job, candidate, facts, asOf, scenario.code())).toList();
            var value = projected(job, candidate, facts, asOf, current.code(), targetYear);
            return new JobProjection(job.jobId(), outcomes, value.historicalActual(), value.targetYearAnalog());
        }).toList();
    }

    private static JobScenarioOutcome renamed(JobScenarioOutcome value, String scenarioCode) {
        return new JobScenarioOutcome(scenarioCode, value.outcome(), value.reasons());
    }

    private static JobScenarioOutcome merge(JobScenarioOutcome base, QualificationOutcome graduateOutcome,
        List<String> graduateReasons, String scenarioCode) {
        var reasons = new LinkedHashSet<String>();
        reasons.addAll(base.reasons());
        reasons.addAll(graduateReasons);
        return new JobScenarioOutcome(scenarioCode, worsen(base.outcome(), graduateOutcome), List.copyOf(reasons));
    }

    private static JobScenarioOutcome evaluate(HistoricalJob job, CandidateProfile candidate, CandidateFacts facts,
        LocalDate asOf, String scenarioCode) {
        return evaluate(job, candidate, facts, asOf, scenarioCode, false, job.year());
    }

    private static JobScenarioOutcome evaluate(HistoricalJob job, CandidateProfile candidate, CandidateFacts facts,
        LocalDate asOf, String scenarioCode, boolean targetYearAnalog, int targetYear) {
        QualificationOutcome outcome = QualificationOutcome.ELIGIBLE;
        var reasons = new ArrayList<String>();
        if (job.minimumEducation().ordinal() > EducationLevel.BACHELOR.ordinal()) {
            if (scenarioCode.equals("PRE_GRADUATION") || scenarioCode.equals("MASTER_IN_PROGRESS")) {
                if (!facts.isConfirmed(CandidateFactKey.HIGHEST_EDUCATION)) {
                    outcome = worsen(outcome, QualificationOutcome.UNCERTAIN);
                    reasons.add("已完成学历尚未确认");
                } else {
                    outcome = worsen(outcome, QualificationOutcome.CONDITIONALLY_ELIGIBLE);
                    reasons.add(scenarioCode.equals("MASTER_IN_PROGRESS")
                        ? "境外硕士在读；学位与留服认证需在公告要求时点前完成"
                        : "当前仅按已完成本科判断；硕士预计取得后需按公告期限复核");
                }
            } else if (scenarioCode.equals("DEGREE_PENDING_VERIFICATION")) {
                switch (credentialTiming(job)) {
                    case REQUIRED_BY_APPLICATION -> {
                        outcome = QualificationOutcome.INELIGIBLE;
                        reasons.add("公告要求在报名或资格审查截止前取得留服认证；待认证场景不满足该截止条件");
                    }
                    case REQUIRED_LATER -> {
                        outcome = worsen(outcome, QualificationOutcome.CONDITIONALLY_ELIGIBLE);
                        reasons.add("报名阶段可暂按硕士已取得判断，但须在后续资格复审、录用或报到前完成留服认证");
                    }
                    case AMBIGUOUS -> {
                        outcome = worsen(outcome, QualificationOutcome.UNCERTAIN);
                        reasons.add("公告要求留服认证，但认证截止阶段不明确，需核对公告原文或咨询招考单位");
                    }
                    case NOT_REQUIRED -> {
                        outcome = worsen(outcome, QualificationOutcome.CONDITIONALLY_ELIGIBLE);
                        reasons.add("硕士已取得但留服认证待完成；公告认证截止要求需复核");
                    }
                }
            } else if (!facts.isConfirmed(CandidateFactKey.EDUCATION_RECORDS)) {
                outcome = worsen(outcome, QualificationOutcome.UNCERTAIN);
                reasons.add("硕士学历与认证材料尚未确认");
            }
        } else if (!facts.isConfirmed(CandidateFactKey.HIGHEST_EDUCATION)) {
            outcome = worsen(outcome, QualificationOutcome.UNCERTAIN);
            reasons.add("已完成学历尚未确认");
        }
        if (!job.exactMajors().isEmpty()) {
            if (!facts.isConfirmed(CandidateFactKey.MAJORS) || (!scenarioCode.equals("PRE_GRADUATION") && !facts.isConfirmed(CandidateFactKey.EDUCATION_RECORDS))) {
                outcome = worsen(outcome, QualificationOutcome.UNCERTAIN);
                reasons.add("专业事实尚未完整确认");
            } else if (!matchesMajor(candidate, job.exactMajors(), scenarioCode)) {
                if (hasMajorCategoryRule(job.exactMajors())) {
                    outcome = worsen(outcome, QualificationOutcome.UNCERTAIN);
                    reasons.add("公告使用专业类别表述，需按官方专业目录人工核验");
                } else {
                    outcome = QualificationOutcome.INELIGIBLE;
                    reasons.add("已确认专业不在公告专业范围内");
                }
            }
        }
        if (!targetYearAnalog && !job.acceptedGraduationYears().isEmpty()) {
            Integer graduationYear = scenarioCode.equals("PRE_GRADUATION") ? candidate.graduationYear() : expectedMasterYear(candidate);
            CandidateFactKey key = scenarioCode.equals("PRE_GRADUATION") ? CandidateFactKey.GRADUATION_YEAR : CandidateFactKey.EDUCATION_RECORDS;
            if (!facts.isConfirmed(key) || graduationYear == null) {
                outcome = worsen(outcome, QualificationOutcome.UNCERTAIN);
                reasons.add("毕业年份尚未确认");
            } else if (!job.acceptedGraduationYears().contains(graduationYear)) {
                outcome = QualificationOutcome.INELIGIBLE;
                reasons.add("毕业年份 " + graduationYear + " 不在公告允许范围内");
            }
        }
        if (job.genderRequirement() != null && !job.genderRequirement().isBlank() && !job.genderRequirement().contains("不限")) {
            if (!facts.isConfirmed(CandidateFactKey.GENDER)) {
                outcome = worsen(outcome, QualificationOutcome.UNCERTAIN);
                reasons.add("性别事实尚未确认");
            } else if ((job.genderRequirement().contains("男") && candidate.gender() != Gender.MALE)
                || (job.genderRequirement().contains("女") && candidate.gender() != Gender.FEMALE)) {
                outcome = QualificationOutcome.INELIGIBLE;
                reasons.add("性别不符合公告限定：" + job.genderRequirement());
            }
        }
        if (job.maximumAge() != null) {
            LocalDate referenceDate = targetYearAnalog
                ? shiftByRecruitmentYears(job.ageReferenceDate(), job.year(), targetYear)
                : job.ageReferenceDate();
            if (!facts.isConfirmed(CandidateFactKey.BIRTH_DATE)) {
                outcome = worsen(outcome, QualificationOutcome.UNCERTAIN);
                reasons.add("完整生日事实尚未确认，不能形成年龄硬结论");
            } else if (referenceDate == null || candidate.birthDate().exactDate().isEmpty()) {
                outcome = worsen(outcome, QualificationOutcome.UNCERTAIN);
                reasons.add("年龄参考日或完整生日缺失，不能精确判断");
            } else {
                int age = Period.between(candidate.birthDate().exactDate().orElseThrow(), referenceDate).getYears();
                if (age > job.maximumAge()) {
                    outcome = QualificationOutcome.INELIGIBLE;
                    reasons.add((targetYearAnalog ? "目标年度参考日 " + referenceDate + " 年龄 " : "公告参考日年龄 ")
                        + age + " 周岁，超过 " + job.maximumAge() + " 周岁上限");
                }
            }
        }
        LocalDate experienceAsOf = targetYearAnalog ? shiftToYear(asOf, targetYear) : asOf;
        int relevantYears = verifiedFullTimeYears(candidate, experienceAsOf, Set.of(job.jobFamily()));
        if (job.minimumExperienceYears() != null) {
            if (!facts.isConfirmed(CandidateFactKey.EMPLOYMENT_HISTORY)) {
                outcome = worsen(outcome, QualificationOutcome.UNCERTAIN);
                reasons.add("要求 " + job.minimumExperienceYears() + " 年相关经历，但工作经历事实尚未确认");
            } else if (relevantYears < job.minimumExperienceYears()) {
                outcome = QualificationOutcome.INELIGIBLE;
                reasons.add("要求 " + job.minimumExperienceYears() + " 年相关经历；已确认记录仅有 " + relevantYears + " 个完整年");
            }
        }
        if (!job.requiredProfessionalTitles().isEmpty()) {
            if (!facts.isConfirmed(CandidateFactKey.PROFESSIONAL_TITLES)) {
                outcome = worsen(outcome, QualificationOutcome.UNCERTAIN);
                reasons.add("职称事实尚未确认");
            } else if (!matchesTitle(candidate, job.requiredProfessionalTitles())) {
                outcome = QualificationOutcome.INELIGIBLE;
                reasons.add("已确认职称不符合公告要求");
            }
        }
        String scope = String.join(" ", Objects.toString(job.candidateScope(), ""), Objects.toString(job.requirements(), ""));
        String normalizedScope = scope.replaceAll("\\s+", "");
        switch (politicalRule(scope)) {
            case REQUIRED -> {
                if (!facts.isConfirmed(CandidateFactKey.POLITICAL_AFFILIATION)) {
                    outcome = worsen(outcome, QualificationOutcome.UNCERTAIN);
                    reasons.add("岗位限定中共党员，但政治面貌尚未确认");
                } else if (candidate.politicalAffiliation() == PoliticalAffiliation.NON_MEMBER) {
                    outcome = QualificationOutcome.INELIGIBLE;
                    reasons.add("岗位限定中共党员，画像确认为非党员");
                } else if (candidate.politicalAffiliation() == PoliticalAffiliation.UNKNOWN) {
                    outcome = worsen(outcome, QualificationOutcome.UNCERTAIN);
                    reasons.add("岗位限定中共党员，但政治面貌尚未确认");
                }
            }
            case PREFERENCE -> reasons.add("党员为优先条件，不按报考硬门槛处理");
            case AMBIGUOUS -> {
                outcome = worsen(outcome, QualificationOutcome.UNCERTAIN);
                reasons.add("政治面貌条件含备选或歧义，需按公告原文人工核验");
            }
            case NONE -> { }
        }
        if (job.graduateEligibilityRule() == null
            && (normalizedScope.contains("应届毕业生") || normalizedScope.contains("应届生"))) {
            outcome = worsen(outcome, QualificationOutcome.UNCERTAIN);
            reasons.add("应届身份需按当年公告、毕业时间和社保经历复核");
        }
        if (!job.evidenceComplete()) {
            outcome = worsen(outcome, QualificationOutcome.UNCERTAIN);
            reasons.add("岗位关键证据尚不完整");
        }
        if (reasons.isEmpty()) reasons.add("已采集硬条件未发现阻断项；报名时仍须以当年公告复核");
        return new JobScenarioOutcome(scenarioCode, outcome, reasons);
    }

    private static LocalDate shiftByRecruitmentYears(LocalDate value, int sourceYear, int targetYear) {
        if (value == null || sourceYear < 2000 || sourceYear > 2100) return null;
        return value.plusYears(targetYear - sourceYear);
    }

    private static LocalDate shiftToYear(LocalDate value, int targetYear) {
        if (value == null) return null;
        return value.plusYears(targetYear - value.getYear());
    }

    private static QualificationOutcome worsen(QualificationOutcome current, QualificationOutcome candidate) {
        return severity(candidate) > severity(current) ? candidate : current;
    }

    private static int severity(QualificationOutcome value) {
        return switch (value) {
            case ELIGIBLE -> 0; case CONDITIONALLY_ELIGIBLE -> 1; case UNCERTAIN -> 2; case INELIGIBLE -> 3;
        };
    }

    private static boolean matchesTitle(CandidateProfile candidate, Set<String> required) {
        String owned = String.join(" ", candidate.professionalTitles()).replace("职称", "").toLowerCase();
        return required.stream().map(value -> value.replace("职称", "").toLowerCase())
            .anyMatch(value -> !value.isBlank() && (owned.contains(value) || value.contains(owned)));
    }

    private static boolean matchesMajor(CandidateProfile candidate, List<String> required, String scenarioCode) {
        var owned = new ArrayList<String>(candidate.majors());
        if (!scenarioCode.equals("PRE_GRADUATION")) candidate.educationRecords().stream()
            .filter(record -> record.educationLevel() == EducationLevel.MASTER)
            .map(record -> record.majorName()).forEach(owned::add);
        return owned.stream().map(CareerPlanService::normalizeMajor).anyMatch(candidateMajor ->
            required.stream().filter(rule -> !isMajorCategoryRule(rule)).map(CareerPlanService::normalizeMajor)
                .anyMatch(rule -> !rule.isBlank() && rule.equals(candidateMajor)));
    }

    private static String normalizeMajor(String value) {
        return value == null ? "" : value.replaceAll("[\\s、，,；;（）()]", "")
            .replace("专业", "").toLowerCase(Locale.ROOT);
    }

    private static boolean hasMajorCategoryRule(List<String> required) {
        return required.stream().anyMatch(CareerPlanService::isMajorCategoryRule);
    }

    private static boolean isMajorCategoryRule(String value) {
        if (value == null) return false;
        String normalized = value.replaceAll("[\\s、，,；;（）()]", "");
        return normalized.contains("类") || normalized.contains("专业目录");
    }

    private enum CredentialTiming { NOT_REQUIRED, REQUIRED_BY_APPLICATION, REQUIRED_LATER, AMBIGUOUS }

    private static CredentialTiming credentialTiming(HistoricalJob job) {
        String rule = Objects.toString(job.overseasDegreeRule(), "");
        CredentialTiming result = CredentialTiming.NOT_REQUIRED;
        for (String rawClause : rule.split("[。；;，,\\r\\n]+")) {
            String clause = rawClause.replaceAll("\\s+", "");
            if (!clause.contains("认证") || containsAny(clause, "无需认证", "不需认证", "不要求认证")) continue;
            if (!containsAny(clause, "必须", "须", "需", "应当", "应提供", "应取得", "应完成")) continue;
            boolean applicationStage = containsAny(clause,
                "报名前", "报名时", "报名截止", "资格审查前", "资格审查时", "资格审查截止",
                "资格初审前", "资格初审时", "材料审核前", "材料审核时");
            boolean laterStage = containsAny(clause,
                "录用前", "录用时", "报到前", "报到时", "聘用前", "聘用时", "入职前", "入职时",
                "考察前", "考察时", "体检前", "体检时", "资格复审前", "资格复审时");
            if (applicationStage && laterStage) {
                return CredentialTiming.AMBIGUOUS;
            }
            if (applicationStage) {
                return CredentialTiming.REQUIRED_BY_APPLICATION;
            }
            if (laterStage) {
                result = CredentialTiming.REQUIRED_LATER;
            } else if (result == CredentialTiming.NOT_REQUIRED) {
                result = CredentialTiming.AMBIGUOUS;
            }
        }
        return result;
    }

    private enum PoliticalRule { NONE, REQUIRED, PREFERENCE, AMBIGUOUS }

    private static PoliticalRule politicalRule(String scope) {
        PoliticalRule result = PoliticalRule.NONE;
        for (String rawClause : Objects.toString(scope, "").split("[。；;，,\\r\\n]+")) {
            String clause = rawClause.replaceAll("\\s+", "");
            if (!clause.contains("党员")) continue;
            if (clause.contains("优先") || clause.contains("不限")) {
                if (result == PoliticalRule.NONE) result = PoliticalRule.PREFERENCE;
            } else if (containsAny(clause, "民主党派", "党员或", "或党员", "非中共党员", "非党员")) {
                if (result != PoliticalRule.REQUIRED) result = PoliticalRule.AMBIGUOUS;
            } else {
                return PoliticalRule.REQUIRED;
            }
        }
        return result;
    }

    private static Integer expectedMasterYear(CandidateProfile candidate) {
        return candidate.educationRecords().stream().filter(record -> record.educationLevel() == EducationLevel.MASTER)
            .map(record -> record.graduationYear()).filter(Objects::nonNull).min(Integer::compareTo).orElse(null);
    }

    private static int verifiedFullTimeYears(CandidateProfile candidate, LocalDate asOf) {
        return verifiedFullTimeYears(candidate, asOf, Set.of());
    }

    private static int verifiedFullTimeYears(CandidateProfile candidate, LocalDate asOf, Set<JobFamily> families) {
        var ranges = candidate.employmentRecords().stream()
            .filter(record -> record.verificationStatus() == VerificationStatus.VERIFIED)
            .filter(record -> record.employmentMode() == EmploymentMode.FULL_TIME)
            .filter(record -> families.isEmpty() || employmentRelevant(record, families))
            .map(record -> new DateRange(record.startsOn(), min(record.endsOn() == null ? asOf : record.endsOn(), asOf)))
            .filter(range -> !range.end().isBefore(range.start())).sorted(Comparator.comparing(DateRange::start)).toList();
        if (ranges.isEmpty()) return 0;
        long days = 0;
        LocalDate start = ranges.getFirst().start();
        LocalDate end = ranges.getFirst().end();
        for (int index = 1; index < ranges.size(); index++) {
            DateRange next = ranges.get(index);
            if (!next.start().isAfter(end.plusDays(1))) end = end.isAfter(next.end()) ? end : next.end();
            else { days += ChronoUnit.DAYS.between(start, end.plusDays(1)); start = next.start(); end = next.end(); }
        }
        days += ChronoUnit.DAYS.between(start, end.plusDays(1));
        return (int) (days / 365);
    }

    private static boolean employmentRelevant(CandidateEmploymentRecord record, Set<JobFamily> families) {
        String text = (record.roleTitle() + " " + record.employerName()).toLowerCase();
        for (JobFamily family : families) {
            boolean matches = switch (family) {
                case SOFTWARE -> containsAny(text, "开发", "软件", "java", "程序", "研发");
                case INFORMATION_SYSTEMS -> containsAny(text, "信息", "系统", "数字化", "数据", "网络", "运维", "开发", "it");
                case DATA -> containsAny(text, "数据", "数据库", "算法", "分析", "人工智能", "ai");
                case AI -> containsAny(text, "人工智能", "算法", "机器学习", "大模型", "ai");
                case CYBERSECURITY -> containsAny(text, "安全", "网络", "等保", "攻防");
                case DIGITALIZATION -> containsAny(text, "数字化", "信息", "系统", "数据", "开发");
                case IT_OPERATIONS -> containsAny(text, "运维", "网络", "系统", "信息", "it");
                case RESEARCH -> containsAny(text, "研究", "科研", "实验", "课题", "研发");
                case PRODUCT -> containsAny(text, "产品", "项目", "需求", "信息化", "数字化");
                case OTHER -> true;
            };
            if (matches) return true;
        }
        return false;
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private static LocalDate min(LocalDate left, LocalDate right) { return left.isBefore(right) ? left : right; }
    private record DateRange(LocalDate start, LocalDate end) {}

    private static EvidenceStrength strength(List<HistoricalJob> rows, int events) {
        int jobs = rows.size();
        long complete = rows.stream().filter(HistoricalJob::evidenceComplete).count();
        if (jobs > 0 && complete * 100 / jobs < 60) return EvidenceStrength.LIMITED;
        if (jobs >= 20 && events >= 5) return EvidenceStrength.STRONG;
        if (events >= 5) return EvidenceStrength.MODERATE;
        if (events >= 3) return EvidenceStrength.LIMITED;
        return EvidenceStrength.INSUFFICIENT;
    }

    private static List<AgeWindow> ageWindows(CandidateProfile candidate, CandidateFacts facts) {
        if (!facts.isConfirmed(CandidateFactKey.BIRTH_DATE)) return List.of();
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
                    "按 " + birth + " 与春季参考日精确计算", true));
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

    private static ExamSummary examSummary(List<HistoricalJob> jobs) {
        List<HistoricalJob> events = List.copyOf(uniqueEvents(jobs).values());
        var intervals = events.stream()
            .filter(job -> job.writtenExamState() == EvidenceState.CONFIRMED)
            .filter(job -> job.applicationStartsOn() != null && job.writtenExamOn() != null)
            .mapToLong(job -> ChronoUnit.DAYS.between(job.applicationStartsOn(), job.writtenExamOn()))
            .filter(days -> days >= 0).boxed().toList();
        Integer averageInterval = intervals.isEmpty() ? null
            : (int) Math.round(intervals.stream().mapToLong(Long::longValue).average().orElseThrow());
        var methods = events.stream()
            .filter(job -> job.interviewState() == EvidenceState.CONFIRMED)
            .map(HistoricalJob::interviewMethod)
            .filter(value -> value != null && !value.isBlank())
            .collect(Collectors.groupingBy(value -> value, Collectors.counting())).entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .map(entry -> new ExamPattern(entry.getKey(), entry.getValue().intValue())).toList();
        return new ExamSummary(events.size(),
            stateCount(events, ProcessStateField.WRITTEN, EvidenceState.CONFIRMED),
            stateCount(events, ProcessStateField.WRITTEN, EvidenceState.NOT_REQUIRED),
            stateCount(events, ProcessStateField.WRITTEN, EvidenceState.NOT_PUBLISHED),
            stateCount(events, ProcessStateField.WRITTEN, EvidenceState.NOT_COLLECTED),
            stateCount(events, ProcessStateField.WRITTEN, EvidenceState.PARSE_FAILED),
            stateCount(events, ProcessStateField.WRITTEN, EvidenceState.REVIEW_REQUIRED),
            stateCount(events, ProcessStateField.WRITTEN, EvidenceState.UNKNOWN),
            stateCount(events, ProcessStateField.PROFESSIONAL_TEST, EvidenceState.CONFIRMED),
            stateCount(events, ProcessStateField.PROFESSIONAL_TEST, EvidenceState.NOT_REQUIRED),
            stateCount(events, ProcessStateField.PROFESSIONAL_TEST, EvidenceState.NOT_PUBLISHED),
            stateCount(events, ProcessStateField.PROFESSIONAL_TEST, EvidenceState.NOT_COLLECTED),
            stateCount(events, ProcessStateField.PROFESSIONAL_TEST, EvidenceState.PARSE_FAILED),
            stateCount(events, ProcessStateField.PROFESSIONAL_TEST, EvidenceState.REVIEW_REQUIRED),
            stateCount(events, ProcessStateField.PROFESSIONAL_TEST, EvidenceState.UNKNOWN),
            stateCount(events, ProcessStateField.INTERVIEW, EvidenceState.CONFIRMED),
            stateCount(events, ProcessStateField.INTERVIEW, EvidenceState.NOT_REQUIRED),
            stateCount(events, ProcessStateField.INTERVIEW, EvidenceState.NOT_PUBLISHED),
            stateCount(events, ProcessStateField.INTERVIEW, EvidenceState.NOT_COLLECTED),
            stateCount(events, ProcessStateField.INTERVIEW, EvidenceState.PARSE_FAILED),
            stateCount(events, ProcessStateField.INTERVIEW, EvidenceState.REVIEW_REQUIRED),
            stateCount(events, ProcessStateField.INTERVIEW, EvidenceState.UNKNOWN),
            examPatterns(jobs), methods, intervals.size(), averageInterval);
    }

    private enum ProcessStateField { WRITTEN, PROFESSIONAL_TEST, INTERVIEW }

    private static int stateCount(List<HistoricalJob> events, ProcessStateField field, EvidenceState state) {
        return (int) events.stream().map(job -> switch (field) {
            case WRITTEN -> job.writtenExamState();
            case PROFESSIONAL_TEST -> job.professionalTestState();
            case INTERVIEW -> job.interviewState();
        }).filter(value -> value == state).count();
    }

    private static List<ProcessWindow> processWindows(List<HistoricalJob> jobs) {
        List<HistoricalJob> events = List.copyOf(uniqueEvents(jobs).values());
        var result = new ArrayList<ProcessWindow>();
        addProcessWindows(result, "NOTICE", events.stream().map(HistoricalJob::publishedOn).toList());
        addProcessWindows(result, "APPLICATION_START", events.stream().map(HistoricalJob::applicationStartsOn).toList());
        addProcessWindows(result, "WRITTEN_EXAM", events.stream().map(HistoricalJob::writtenExamOn).toList());
        addProcessWindows(result, "INTERVIEW", events.stream().map(HistoricalJob::interviewOn).toList());
        return List.copyOf(result);
    }

    private static void addProcessWindows(List<ProcessWindow> target, String stage, List<LocalDate> dates) {
        dates.stream().filter(Objects::nonNull).collect(Collectors.groupingBy(LocalDate::getMonthValue,
                TreeMap::new, Collectors.counting()))
            .forEach((month, count) -> target.add(new ProcessWindow(stage, month, count.intValue())));
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
        if (data.failedSections().contains("HISTORY")) warnings.add("历史岗位统计本次读取失败；页面中的空值不得解释为零招聘。");
        if (data.failedSections().contains("COVERAGE")) warnings.add("来源覆盖账本本次读取失败；完整性状态暂不可判断。");
        if (data.failedSections().contains("TARGET_SOURCES")) warnings.add("目标来源目录本次读取失败；市场接入度暂不可判断。");
        if (!data.targetSources().isEmpty() && data.targetSources().stream()
            .anyMatch(source -> source.connectionStatus() != ConnectionStatus.CONNECTED)) {
            warnings.add("目标市场来源尚未全部接入；已配置来源完整不代表杭州半体制市场完整。");
        }
        return new DataCoverage(allComplete && data.failedSections().isEmpty(), data.coverage().size(), complete,
            incomplete, warnings, data.failedSections(), data.loadedAt());
    }

    private static ConfiguredCoverage configuredCoverage(DataCoverage legacy) {
        return new ConfiguredCoverage(legacy.complete(), legacy.sourceYearCount(),
            legacy.completeSourceYearCount(), legacy.incompleteSourceYears());
    }

    private static TargetMarketCoverage targetMarketCoverage(List<TargetSource> targets) {
        var routes = List.of("PUBLIC_TECH", "UNIVERSITY_HOSPITAL_IT", "RESEARCH_SUPPORT", "GOVERNMENT_SOE_DIGITAL")
            .stream().map(route -> routeCoverage(route, targets)).toList();
        return new TargetMarketCoverage(targets.size(), connectionCount(targets, ConnectionStatus.CONNECTED),
            connectionCount(targets, ConnectionStatus.PARTIAL), connectionCount(targets, ConnectionStatus.FAILED),
            connectionCount(targets, ConnectionStatus.NOT_CONNECTED), routes);
    }

    private static RouteCoverage routeCoverage(String route, List<TargetSource> targets) {
        var values = targets.stream().filter(target -> target.routeCode().equals(route)).toList();
        int connected = connectionCount(values, ConnectionStatus.CONNECTED);
        return new RouteCoverage(route, values.size(), connected,
            connectionCount(values, ConnectionStatus.PARTIAL), connectionCount(values, ConnectionStatus.FAILED),
            connectionCount(values, ConnectionStatus.NOT_CONNECTED), !values.isEmpty() && connected == values.size());
    }

    private static int connectionCount(List<TargetSource> targets, ConnectionStatus status) {
        return (int) targets.stream().filter(target -> target.connectionStatus() == status).count();
    }

    private static AnalysisCoverage analysisCoverage(List<HistoricalJob> jobs, List<CoverageSignal> coverage,
        Instant loadedAt) {
        Instant analyzedLoadedAt = jobs.stream().map(HistoricalJob::sourceLoadedAt).filter(Objects::nonNull)
            .max(Instant::compareTo).orElse(loadedAt);
        return new AnalysisCoverage((int) jobs.stream().map(HistoricalJob::sourceCode)
            .filter(value -> value != null && !value.isBlank()).distinct().count(),
            (int) jobs.stream().map(HistoricalJob::eventId).distinct().count(), jobs.size(),
            (int) jobs.stream().filter(HistoricalJob::evidenceComplete).count(), analyzedLoadedAt);
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
        var planned = List.of(
            new ActionItem(asOf, LocalDate.of(asOf.getYear(), 12, 31), "核实画像与材料证据", "确认工作起止日期、证明类型、政治面貌和硕士预计毕业月。", "NOW"),
            new ActionItem(LocalDate.of(targetYear, 1, 1), LocalDate.of(targetYear, 3, 31), "监控集中公告并定版报名材料", "重点关注浙江、杭州人社和目标单位官方来源。", "PLANNED"),
            new ActionItem(LocalDate.of(targetYear, 3, 1), LocalDate.of(targetYear, 5, 31), "准备统考与专业基础", "按历史科目复习职测、综应、信息系统、数据库和网络基础。", "PLANNED"),
            new ActionItem(LocalDate.of(targetYear, 6, 1), LocalDate.of(targetYear, 9, 30), "毕业、留服与硕士岗位切换", "取得学位后提交留服认证，并重新计算硕士门槛岗位。", "PLANNED"),
            new ActionItem(LocalDate.of(targetYear + 1, 1, 1), LocalDate.of(targetYear + 1, 5, 31), "进入第二个完整春季周期", "使用上一周期结果调整路线与材料。", "PLANNED")
        );
        return planned.stream().filter(item -> !item.endsOn().isBefore(asOf))
            .map(item -> new ActionItem(item.startsOn().isBefore(asOf) ? asOf : item.startsOn(), item.endsOn(),
                item.title(), item.detail(), !item.startsOn().isAfter(asOf) ? "NOW" : item.status()))
            .sorted(Comparator.comparing(ActionItem::startsOn).thenComparing(ActionItem::endsOn).thenComparing(ActionItem::title))
            .toList();
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
