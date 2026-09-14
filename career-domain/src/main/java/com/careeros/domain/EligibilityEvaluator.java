package com.careeros.domain;

import com.careeros.domain.DomainEnums.EducationLevel;
import com.careeros.domain.EducationRecord.CompletionStatus;
import com.careeros.domain.EducationRecord.CredentialVerificationStatus;
import com.careeros.domain.DomainEnums.EligibilityStatus;
import com.careeros.domain.DomainEnums.Gender;
import com.careeros.domain.DomainEnums.RuleType;
import com.careeros.domain.EligibilityAssessment.RuleResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.EnumMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static com.careeros.domain.CandidateFacts.CandidateFactKey.*;

public final class EligibilityEvaluator {
    // Recompute snapshots produced before unverified/alternative clauses were kept pending.
    public static final String VERSION = "eligibility-hard-verdict-v8";

    public EligibilityAssessment evaluate(CandidateProfile candidate, JobPosting job) {
        return evaluate(candidate, CandidateFacts.confirmed(candidate), job, "unversioned", Instant.now());
    }

    public EligibilityAssessment evaluate(CandidateProfile candidate, JobPosting job, Instant now) {
        return evaluate(candidate, CandidateFacts.confirmed(candidate), job, "unversioned", now);
    }

    public EligibilityAssessment evaluate(CandidateProfile candidate, JobPosting job, String jobContentFingerprint, Instant now) {
        return evaluate(candidate, CandidateFacts.confirmed(candidate), job, jobContentFingerprint, now);
    }

    public EligibilityAssessment evaluate(CandidateProfile candidate, CandidateFacts facts, JobPosting job) {
        return evaluate(candidate, facts, job, "unversioned", Instant.now());
    }

    public EligibilityAssessment evaluate(CandidateProfile candidate, CandidateFacts facts, JobPosting job, String jobContentFingerprint, Instant now) {
        return evaluate(candidate, facts, job, jobContentFingerprint,
            now.atZone(java.time.ZoneOffset.UTC).toLocalDate(), now);
    }

    public EligibilityAssessment evaluate(CandidateProfile candidate, CandidateFacts facts, JobPosting job,
                                          String jobContentFingerprint, LocalDate qualificationAsOf,
                                          Instant assessedAt) {
        return evaluate(candidate, facts, job, jobContentFingerprint, qualificationAsOf, assessedAt, VERSION);
    }

    public EligibilityAssessment evaluate(CandidateProfile candidate, CandidateFacts facts, JobPosting job,
                                          String jobContentFingerprint, LocalDate qualificationAsOf,
                                          Instant assessedAt, String evaluatorVersion) {
        return evaluate(candidate, facts, job, jobContentFingerprint, qualificationAsOf, assessedAt,
            evaluatorVersion, Set.of());
    }

    /**
     * @param conflictingOfficialFields 官方来源互相矛盾的字段名（与 OfficialJobAdmissionService
     *        使用的字段名一致）。依赖这些字段的规则不产出判定——手里的岗位要求本身就不可信，
     *        此时说“满足”或“不满足”都是在替官方做决定。
     */
    public EligibilityAssessment evaluate(CandidateProfile candidate, CandidateFacts facts, JobPosting job,
                                          String jobContentFingerprint, LocalDate qualificationAsOf,
                                          Instant assessedAt, String evaluatorVersion,
                                          Set<String> conflictingOfficialFields) {
        return evaluate(candidate, facts, job, jobContentFingerprint, qualificationAsOf, assessedAt,
            evaluatorVersion, conflictingOfficialFields, null);
    }

    /**
     * @param graduateRule 招聘事件上已解析的应届身份条款。为 null 表示公告里没有这类条款，
     *        因此不构成限制；解析失败或未采集同样是 null，说明里会点明结论只基于已解析内容。
     */
    public EligibilityAssessment evaluate(CandidateProfile candidate, CandidateFacts facts, JobPosting job,
                                          String jobContentFingerprint, LocalDate qualificationAsOf,
                                          Instant assessedAt, String evaluatorVersion,
                                          Set<String> conflictingOfficialFields,
                                          GraduateEligibilityRule graduateRule) {
        return evaluate(candidate, facts, job, jobContentFingerprint, qualificationAsOf, assessedAt,
            evaluatorVersion, conflictingOfficialFields, graduateRule, Map.of());
    }

    /**
     * @param evidenceByOfficialField 官方字段名 → 支撑该字段的证据片段 ID。产品需求 §10.6
     *        要求每个资格结论都有可定位证据；挂在整份评估上的公告级证据只能指到一份公告，
     *        指不到具体是哪一句话。缺片段时该规则的 evidenceIds 为空，读者回落到公告级。
     */
    public EligibilityAssessment evaluate(CandidateProfile candidate, CandidateFacts facts, JobPosting job,
                                          String jobContentFingerprint, LocalDate qualificationAsOf,
                                          Instant assessedAt, String evaluatorVersion,
                                          Set<String> conflictingOfficialFields,
                                          GraduateEligibilityRule graduateRule,
                                          Map<String, List<UUID>> evidenceByOfficialField) {
        Map<String, List<UUID>> fieldEvidence =
            evidenceByOfficialField == null ? Map.of() : evidenceByOfficialField;
        Set<String> conflicts = conflictingOfficialFields == null ? Set.of() : conflictingOfficialFields;
        var results = new EnumMap<RuleType, RuleResult>(RuleType.class);
        results.put(RuleType.AGE, cite(fieldEvidence, "ageRequirementText",
            orConflict(conflicts, "ageRequirementText", () -> evaluateAge(candidate, facts, job))));
        results.put(RuleType.EDUCATION, cite(fieldEvidence, "educationRequirementText",
            orConflict(conflicts, "educationRequirementText", () -> evaluateEducation(candidate, facts, job))));
        results.put(RuleType.EXACT_MAJOR, cite(fieldEvidence, "majorRequirementText",
            orConflict(conflicts, "majorRequirementText", () -> evaluateMajor(candidate, facts, job))));
        results.put(RuleType.GRADUATE_YEAR, cite(fieldEvidence, "graduateRule",
            evaluateGraduation(candidate, facts, job)));
        results.put(RuleType.EXPERIENCE, cite(fieldEvidence, "experienceEvidenceRule",
            evaluateExperience(candidate, facts, job, qualificationAsOf)));
        results.put(RuleType.PROFESSIONAL_TITLE, evaluateProfessionalTitle(candidate, facts, job));
        results.put(RuleType.POLITICAL_AFFILIATION, cite(fieldEvidence, "otherRequirements",
            evaluatePoliticalAffiliation(candidate, facts, job)));
        results.put(RuleType.GENDER, cite(fieldEvidence, "genderRequirement",
            evaluateGender(candidate, facts, job)));
        results.put(RuleType.FRESH_GRADUATE_STATUS, cite(fieldEvidence, "graduateRule",
            evaluateFreshGraduateStatus(candidate, facts, graduateRule)));
        var overall = results.values().stream().map(RuleResult::status)
            .max(EligibilityEvaluator::compareSeverity).orElse(EligibilityStatus.NEEDS_CONFIRMATION);
        return new EligibilityAssessment(UUID.randomUUID(), candidate.id(), job.id(), overall, results, job.evidenceIds(), evaluatorVersion, assessedAt, candidate.profileVersion(), jobContentFingerprint);
    }

    /** 给一条规则结果挂上对应官方字段的证据片段；没有片段时保持为空，由读者回落到公告级。 */
    private static RuleResult cite(Map<String, List<UUID>> fieldEvidence, String officialField, RuleResult result) {
        List<UUID> fragments = fieldEvidence.get(officialField);
        return fragments == null || fragments.isEmpty() ? result : result.withEvidence(fragments);
    }

    private static RuleResult orConflict(
        Set<String> conflicts, String officialField, java.util.function.Supplier<RuleResult> rule
    ) {
        return conflicts.contains(officialField)
            ? conflicting("官方来源在「" + officialField + "」上互相矛盾，该条无法判定")
            : rule.get();
    }

    RuleResult evaluateAge(CandidateProfile candidate, CandidateFacts facts, JobPosting job) {
        if (job.maximumAge() == null) return needsConfirmation("岗位年龄要求缺失或尚未明确，无法确认是否符合");
        if (job.ageReferenceDate() == null) return needsConfirmation("岗位有年龄上限，但缺少年龄计算基准日");
        if (!facts.isConfirmed(BIRTH_DATE)) return needsConfirmation("候选人出生日期尚未确认");
        int youngest = Period.between(candidate.birthDate().latest(), job.ageReferenceDate()).getYears();
        int oldest = Period.between(candidate.birthDate().earliest(), job.ageReferenceDate()).getYears();
        if (oldest <= job.maximumAge()) return eligible("基准日年龄不超过 " + job.maximumAge() + " 周岁");
        if (youngest > job.maximumAge()) return ineligible("基准日年龄超过 " + job.maximumAge() + " 周岁");
        return needsConfirmation("出生日期仅精确到月份，处于年龄边界，无法唯一判定");
    }

    RuleResult evaluateEducation(CandidateProfile candidate, CandidateFacts facts, JobPosting job) {
        if (job.minimumEducation() == EducationLevel.UNKNOWN) return needsConfirmation("岗位最低学历信息缺失");
        if (!facts.isConfirmed(HIGHEST_EDUCATION)) return needsConfirmation("候选人学历尚未确认");
        if (candidate.highestEducation() == EducationLevel.UNKNOWN) return needsConfirmation("候选人学历信息缺失");
        if (rank(candidate.highestEducation()) < rank(job.minimumEducation())) {
            return ineligible("学历低于 " + job.minimumEducation());
        }
        return settledEducation(candidate, job);
    }

    /**
     * 学历达标之后，还要看支撑它的那段学历本身是否已经落定。
     *
     * <p>产品需求 §6.1 的 CONDITIONAL 就是为这种情况准备的：候选人计划以境外硕士身份报考，
     * 学位尚未取得或留服认证尚未完成——结论不是“可报”，也不是“待确认”，而是“取决于某件
     * 尚未完成的事”。只要有一段已毕业且认证已完成（或无需认证）的学历能单独满足要求，
     * 就不存在这个条件。
     *
     * <p>没有逐段学历记录时退回按 highestEducation 判定：旧资料只有一个汇总字段，
     * 不能因为没填明细就把已经满足的学历降级成条件式结论。
     */
    private static RuleResult settledEducation(CandidateProfile candidate, JobPosting job) {
        var qualifying = candidate.educationRecords().stream()
            .filter(record -> rank(record.educationLevel()) >= rank(job.minimumEducation()))
            .toList();
        if (qualifying.isEmpty()) return eligible("学历满足最低要求");
        if (qualifying.stream().anyMatch(EligibilityEvaluator::settled)) {
            return eligible("学历满足最低要求，且已有一段已毕业并完成认证的学历可单独支撑");
        }
        var pending = qualifying.getFirst();
        if (pending.completionStatus() == CompletionStatus.EXPECTED) {
            return conditional("学历达标取决于" + expectedGraduation(pending) + "按期毕业");
        }
        return switch (pending.credentialVerificationStatus()) {
            case PLANNED, IN_PROGRESS -> conditional("学历达标取决于境外学历认证完成");
            case UNKNOWN -> needsConfirmation("境外学历是否需要认证、认证是否完成尚未确认");
            case VERIFIED, NOT_REQUIRED -> eligible("学历满足最低要求");
        };
    }

    /** 已毕业，且认证已完成或本来就不需要认证。 */
    private static boolean settled(EducationRecord record) {
        return record.completionStatus() == CompletionStatus.COMPLETED
            && (record.credentialVerificationStatus() == CredentialVerificationStatus.VERIFIED
                || record.credentialVerificationStatus() == CredentialVerificationStatus.NOT_REQUIRED);
    }

    private static String expectedGraduation(EducationRecord record) {
        if (record.graduationYear() == null) return "该段学历";
        return record.graduationMonth() == null
            ? record.graduationYear() + " 年"
            : record.graduationYear() + " 年 " + record.graduationMonth() + " 月";
    }

    RuleResult evaluateMajor(CandidateProfile candidate, CandidateFacts facts, JobPosting job) {
        if (job.exactMajors().isEmpty()) {
            return unparsedOrUnrestricted(job.majorRequirementText(), "专业", "岗位未限定精确专业目录");
        }
        if (!facts.isConfirmed(MAJORS)) return needsConfirmation("候选人专业尚未确认");
        if (candidate.majors().isEmpty()) return needsConfirmation("候选人专业信息缺失");
        Set<String> allowed = job.exactMajors().stream().map(EligibilityEvaluator::normalize).collect(Collectors.toSet());
        Set<String> candidateMajors = candidate.majors().stream()
            .map(EligibilityEvaluator::normalize).collect(Collectors.toSet());
        boolean match = candidateMajors.stream().anyMatch(allowed::contains)
            || job.exactMajors().stream().map(EligibilityEvaluator::normalize)
                .anyMatch(restricted -> candidateMajors.stream()
                    .anyMatch(major -> restricted.endsWith("限" + major)));
        if (match) return eligible("专业名称与允许目录精确匹配");
        boolean taxonomyNeedsReview = job.exactMajors().stream().anyMatch(value -> value.contains("门类") || value.endsWith("类") || value.contains("相关专业"));
        return taxonomyNeedsReview ? needsConfirmation("岗位使用专业门类或宽泛目录，需要权威专业分类表判定") : ineligible("专业名称不在岗位允许目录中");
    }

    RuleResult evaluateGraduation(CandidateProfile candidate, CandidateFacts facts, JobPosting job) {
        if (job.acceptedGraduationYears().isEmpty()) {
            return unparsedOrUnrestricted(job.candidateScope(), "招聘对象", "岗位无毕业届别限制");
        }
        if (!facts.isConfirmed(GRADUATION_YEAR)) return needsConfirmation("候选人毕业年份尚未确认");
        if (candidate.graduationYear() == null) return needsConfirmation("候选人毕业年份缺失");
        return job.acceptedGraduationYears().contains(candidate.graduationYear()) ? eligible("毕业年份满足应届范围") : ineligible("毕业年份不在允许范围内");
    }

    RuleResult evaluateExperience(CandidateProfile candidate, CandidateFacts facts, JobPosting job, LocalDate asOf) {
        if (job.minimumExperienceYears() == null) return needsConfirmation("岗位工作年限要求缺失或尚未明确，无法确认是否符合");
        if (job.minimumExperienceYears() == 0) return eligible("岗位无最低工作年限要求");
        if (asOf == null) return needsConfirmation("岗位缺少官方资格计算截止日，无法核定工作年限");
        var verifiedYears = CandidateEmploymentExperience.completedYears(candidate, facts, asOf);
        if (verifiedYears.isEmpty()) return needsConfirmation("缺少已确认、逐段核验的全职工作经历");
        return verifiedYears.getAsInt() >= job.minimumExperienceYears()
            ? eligible("已核验全职工作年限满足要求")
            : ineligible("已核验全职工作年限不足 " + job.minimumExperienceYears() + " 年");
    }

    RuleResult evaluateProfessionalTitle(CandidateProfile candidate, CandidateFacts facts, JobPosting job) {
        if (job.requiredProfessionalTitles().isEmpty()) {
            return unparsedOrUnrestricted(job.otherRequirements(), "其他条件", "岗位无职称要求");
        }
        if (!facts.isConfirmed(PROFESSIONAL_TITLES)) return needsConfirmation("候选人职称情况尚未确认");
        if (candidate.professionalTitles().isEmpty()) return ineligible("缺少岗位要求的职称");
        Set<String> candidateTitles = candidate.professionalTitles().stream().map(EligibilityEvaluator::normalize).collect(Collectors.toSet());
        boolean match = job.requiredProfessionalTitles().stream().map(EligibilityEvaluator::normalize)
            .anyMatch(required -> candidateTitles.stream().anyMatch(candidateTitle ->
                candidateTitle.equals(required) || candidateTitle.startsWith(required + "：")
                    || candidateTitle.startsWith(required + ":")));
        return match ? eligible("职称满足要求") : ineligible("职称不满足要求");
    }

    /**
     * 政治面貌（产品需求 §5 EligibilityRule）。
     *
     * <p>只认公告里明确写死的硬性要求。"中共党员优先"和"不限"不构成门槛；
     * "党员或民主党派"等未能完整表达的复合条件则需要核对原文，不能当作无限制。
     *
     * <p>预备党员单列：多数公告写"中共党员（含预备党员）"，但也有明确只要正式党员的。
     * 公告原文没说清楚时不替它决定，落到待确认。
     */
    RuleResult evaluatePoliticalAffiliation(CandidateProfile candidate, CandidateFacts facts, JobPosting job) {
        var requirement = POLITICAL_CLASSIFIER.classify(job);
        if (requirement == PoliticalRequirementClassifier.Classification.NO_HARD_REQUIREMENT) {
            return eligible("岗位未限定政治面貌");
        }
        if (requirement == PoliticalRequirementClassifier.Classification.MANUAL_REVIEW) {
            return needsConfirmation("公告政治面貌包含复合或无法可靠解析的条件，需人工核对原文");
        }
        if (!facts.isConfirmed(POLITICAL_AFFILIATION)) return needsConfirmation("候选人政治面貌尚未确认");
        return switch (candidate.politicalAffiliation()) {
            case CPC_MEMBER -> eligible("政治面貌满足公告的中共党员要求");
            case CPC_PROBATIONARY -> needsConfirmation(
                "候选人为中共预备党员，需确认公告是否接受预备党员");
            case NON_MEMBER -> ineligible("公告限定中共党员，候选人不是党员");
            case UNKNOWN -> needsConfirmation("候选人政治面貌信息缺失");
        };
    }

    /**
     * 性别（产品需求 §5 EligibilityRule）。
     *
     * <p>读的是公告已经公开写明的限定，用途只有一个：让候选人不必在报不了的岗位上花时间。
     * "不限"和空值都不构成限制。公告写了限定但看不出限的是哪一性别时落到待确认，不猜。
     */
    RuleResult evaluateGender(CandidateProfile candidate, CandidateFacts facts, JobPosting job) {
        String requirement = job.genderRequirement();
        if (requirement == null || requirement.isBlank()) {
            return eligible("公告未写性别要求");
        }
        if (GENDER_CLASSIFIER.explicitlyUnrestricted(requirement)) {
            // "不限"是事实，"男性优先"是倾向——两者都不构成报名门槛。
            return eligible("公告未把性别设为门槛：" + requirement);
        }
        var restricted = GENDER_CLASSIFIER.hardRequirement(requirement);
        if (restricted.isEmpty()) {
            // 公告写了性别相关文字但读不懂限的是哪一边，这不是"没限制"。
            return needsConfirmation("岗位性别要求无法判定，需核对原文：" + requirement);
        }
        if (!facts.isConfirmed(GENDER)) return needsConfirmation("候选人性别尚未确认");
        if (candidate.gender() == Gender.OTHER || candidate.gender() == Gender.UNKNOWN) {
            return needsConfirmation("候选人性别信息不足以对照公告限定");
        }
        return candidate.gender() == restricted.orElseThrow()
            ? eligible("性别满足公告限定")
            : ineligible("公告限定" + (restricted.orElseThrow() == Gender.MALE ? "男性" : "女性"));
    }

    /**
     * 应届身份（产品需求 §2.2、§5 EligibilityRule）。
     *
     * <p>公告限定"未落实工作单位"或"无社保缴纳记录"时，要求的是**报名当天**的状态，
     * 而报名还没发生。候选人今天无法确认一个未来时点的事实，只能声明一个打算，因此
     * 声明满足只得出条件式结论——把还没兑现的未来当成既成事实，正是基线禁止的。
     * 声明不满足才是明确不可报：那是一个已经确定的、不会再变回去的事实。
     */
    RuleResult evaluateFreshGraduateStatus(
        CandidateProfile candidate, CandidateFacts facts, GraduateEligibilityRule rule
    ) {
        // 三种情况必须分开，压成一件就会把"没读到"说成"没限制"：
        //   null                     —— 公告没采集或没解析，读不到就不能下结论
        //   NOT_REQUIRED             —— 公告处理过、确实没有应届条款，这是可以下结论的事实
        //   有条款但 evidenceState 未确认 —— 条款本身还没核实，先让人核对原文
        if (rule == null) {
            return needsConfirmation("公告的应届身份条款尚未解析，无法确认是否限定应届");
        }
        if (rule.evidenceState() == GraduateEligibilityRule.EvidenceState.NOT_REQUIRED) {
            return eligible("公告已处理，未提出应届身份限制");
        }
        if (rule.evidenceState() != GraduateEligibilityRule.EvidenceState.CONFIRMED) {
            return needsConfirmation("公告的应届身份条款尚未确认，需人工核对原文");
        }
        if (!rule.requiresNoEmployer() && !rule.restrictsSocialInsurance()) {
            return eligible("公告的应届条款未限制工作单位或社保");
        }
        // 两项限定各自独立判定，取最严重的一条。不能判到第一条非 ELIGIBLE 就返回：
        // 工作单位那条是 CONDITIONAL、社保那条是 INELIGIBLE 时，提前返回会把"明确不可报"
        // 掩盖成"条件可报"。
        var results = new java.util.ArrayList<RuleResult>();
        if (rule.requiresNoEmployer()) {
            results.add(declaration(facts, EMPLOYER_SETTLEMENT_AT_APPLICATION,
                candidate.employerSettlementAtApplication(), "未落实工作单位"));
        }
        if (rule.restrictsSocialInsurance()) {
            results.add(declaration(facts, SOCIAL_INSURANCE_AT_APPLICATION,
                candidate.socialInsuranceAtApplication(), "报名时无社保缴纳记录"));
        }
        return results.stream()
            .max((left, right) -> compareSeverity(left.status(), right.status()))
            .filter(worst -> worst.status() != EligibilityStatus.ELIGIBLE)
            .orElseGet(() -> eligible("公告的应届身份条款均已声明满足"));
    }

    private static RuleResult declaration(
        CandidateFacts facts, CandidateFacts.CandidateFactKey key,
        DomainEnums.ApplicationTimeStatus declared, String requirement
    ) {
        if (!facts.isConfirmed(key)) return needsConfirmation("尚未声明" + requirement + "的报名时状态");
        return switch (declared) {
            case DECLARED_MET -> conditional("可报取决于报名时仍然" + requirement);
            case DECLARED_NOT_MET -> ineligible("公告要求" + requirement + "，已声明届时不满足");
            case UNDECLARED -> needsConfirmation("尚未声明" + requirement + "的报名时状态");
        };
    }

    /**
     * 结构化字段为空时，区分"公告确实没限制"和"公告写了但没解析出来"。
     *
     * <p>这两者结论完全相反，而结构化字段为空本身分不出来。原文非空说明公告在这一栏写了
     * 东西、只是没被解析成结构化限制——此时说"无限制"就是在替公告下结论。AGE 与 EXPERIENCE
     * 一开始就按 null 落到待确认，这里把其余规则拉到同一口径。
     */
    private static RuleResult unparsedOrUnrestricted(
        String rawRequirementText, String fieldLabel, String unrestrictedExplanation
    ) {
        if (rawRequirementText == null || rawRequirementText.isBlank()) {
            return eligible(unrestrictedExplanation);
        }
        return needsConfirmation("公告「" + fieldLabel + "」栏有原文但未解析出结构化限制，需核对：" + rawRequirementText);
    }

    private static final GenderRequirementClassifier GENDER_CLASSIFIER =
        new GenderRequirementClassifier();

    private static final PoliticalRequirementClassifier POLITICAL_CLASSIFIER =
        new PoliticalRequirementClassifier();

    private static int rank(EducationLevel level) { return level.ordinal(); }
    private static String normalize(String value) { return Stream.of(value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s·（）()_-]", "")).findFirst().orElse(""); }
    private static int compareSeverity(EligibilityStatus left, EligibilityStatus right) { return Integer.compare(severity(left), severity(right)); }

    /**
     * 逐条结果取最严重的一条作为整体结论。
     *
     * <p>CONDITIONAL 排在 NEEDS_CONFIRMATION 之前：前者已经知道缺的是什么、什么时候能补上，
     * 后者连缺什么都还没确认。CONFLICTING_EVIDENCE 排在 NEEDS_CONFIRMATION 之后：缺证据只需要
     * 去补，证据互相矛盾则要先核对来源，是更重的问题。INELIGIBLE 永远最高——一项硬条件明确
     * 不满足，其余条目再不确定也改变不了结果。
     */
    private static int severity(EligibilityStatus status) {
        return switch (status) {
            case ELIGIBLE -> 0;
            case CONDITIONAL -> 1;
            case NEEDS_CONFIRMATION -> 2;
            case CONFLICTING_EVIDENCE -> 3;
            case INELIGIBLE -> 4;
        };
    }

    private static RuleResult eligible(String message) { return new RuleResult(EligibilityStatus.ELIGIBLE, message); }
    private static RuleResult conditional(String message) { return new RuleResult(EligibilityStatus.CONDITIONAL, message); }
    private static RuleResult needsConfirmation(String message) { return new RuleResult(EligibilityStatus.NEEDS_CONFIRMATION, message); }
    private static RuleResult conflicting(String message) { return new RuleResult(EligibilityStatus.CONFLICTING_EVIDENCE, message); }
    private static RuleResult ineligible(String message) { return new RuleResult(EligibilityStatus.INELIGIBLE, message); }
}
