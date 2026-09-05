package com.careeros.domain;

import com.careeros.domain.DomainEnums.CriterionStatus;
import com.careeros.domain.DomainEnums.EducationLevel;
import com.careeros.domain.DomainEnums.JobField;
import com.careeros.domain.DomainEnums.EligibilityStatus;
import com.careeros.domain.DomainEnums.RuleType;
import com.careeros.domain.EligibilityAssessment.RuleResult;
import java.time.Instant;
import java.time.Period;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 硬条件判定引擎。产品需求 §6.1：资格是硬判定，不是分数——引擎只输出
 * PASS / FAIL / CONDITIONAL / UNKNOWN / NOT_APPLICABLE，聚合时 fail-closed。
 *
 * <p>当前覆盖 6 条规则。§2.2 列出的政治面貌、社保与未就业状态、户籍等条件尚未进入
 * {@link JobPosting}，因此引擎不会为它们伪造 NOT_APPLICABLE——在岗位模型能表达这些条件之前，
 * 它们既不参与判定，也不会被当作"已确认无要求"。
 */
public final class EligibilityEvaluator {
    public static final String VERSION = "phase2-hard-rules-v2";

    public EligibilityAssessment evaluate(CandidateProfile candidate, JobPosting job) {
        return evaluate(candidate, job, Instant.now());
    }

    public EligibilityAssessment evaluate(CandidateProfile candidate, JobPosting job, Instant now) {
        var results = new EnumMap<RuleType, RuleResult>(RuleType.class);
        results.put(RuleType.AGE, evaluateAge(candidate, job, job.evidenceFor(JobField.MAXIMUM_AGE)));
        results.put(RuleType.EDUCATION, evaluateEducation(candidate, job, job.evidenceFor(JobField.MINIMUM_EDUCATION)));
        results.put(RuleType.EXACT_MAJOR, evaluateMajor(candidate, job, job.evidenceFor(JobField.MAJOR_TEXT)));
        results.put(RuleType.GRADUATE_YEAR, evaluateGraduation(candidate, job, job.evidenceFor(JobField.ACCEPTED_GRADUATION_YEARS)));
        results.put(RuleType.EXPERIENCE, evaluateExperience(candidate, job, job.evidenceFor(JobField.MINIMUM_EXPERIENCE_YEARS)));
        // 职称要求目前不在抽取模型里，只能挂公告级证据。
        results.put(RuleType.PROFESSIONAL_TITLE, evaluateProfessionalTitle(candidate, job, job.evidenceIds()));
        return new EligibilityAssessment(
            UUID.randomUUID(), candidate.id(), job.id(), aggregate(results.values()),
            results, requiredConfirmations(results), job.evidenceIds(), VERSION, now);
    }

    /**
     * 聚合顺序即优先级：任一条明确不满足就整体不可报；其次未知优先于条件满足，
     * 避免把"还没查清"说成"满足前提即可"。
     */
    static EligibilityStatus aggregate(Iterable<RuleResult> results) {
        boolean unknown = false;
        boolean conditional = false;
        for (RuleResult result : results) {
            switch (result.status()) {
                case FAIL -> { return EligibilityStatus.INELIGIBLE; }
                case UNKNOWN -> unknown = true;
                case CONDITIONAL -> conditional = true;
                case PASS, NOT_APPLICABLE -> { }
            }
        }
        if (unknown) return EligibilityStatus.NEEDS_CONFIRMATION;
        if (conditional) return EligibilityStatus.CONDITIONAL;
        return EligibilityStatus.ELIGIBLE;
    }

    private static List<String> requiredConfirmations(Map<RuleType, RuleResult> results) {
        return results.entrySet().stream()
            .filter(entry -> entry.getValue().status() == CriterionStatus.UNKNOWN
                || entry.getValue().status() == CriterionStatus.CONDITIONAL)
            .map(entry -> entry.getKey().name() + "：" + entry.getValue().reason())
            .toList();
    }

    RuleResult evaluateAge(CandidateProfile candidate, JobPosting job, List<UUID> evidenceIds) {
        if (job.maximumAge() == null) return notApplicable("岗位未设置最高年龄", evidenceIds);
        String requirement = "不超过 " + job.maximumAge() + " 周岁";
        if (job.ageReferenceDate() == null) {
            return unknown(requirement, birthText(candidate), "岗位有年龄上限，但缺少年龄计算基准日", evidenceIds);
        }
        int youngest = Period.between(candidate.birthDate().latest(), job.ageReferenceDate()).getYears();
        int oldest = Period.between(candidate.birthDate().earliest(), job.ageReferenceDate()).getYears();
        if (oldest <= job.maximumAge()) return pass(requirement, birthText(candidate), "基准日年龄不超过上限", evidenceIds);
        if (youngest > job.maximumAge()) return fail(requirement, birthText(candidate), "基准日年龄超过上限", evidenceIds);
        return unknown(requirement, birthText(candidate),
            "出生日期仅精确到月份，处于年龄边界，无法唯一判定", evidenceIds);
    }

    RuleResult evaluateEducation(CandidateProfile candidate, JobPosting job, List<UUID> evidenceIds) {
        String candidateFact = candidate.highestEducation().name();
        if (job.minimumEducation() == EducationLevel.UNKNOWN) {
            return unknown(null, candidateFact, "岗位最低学历信息缺失，不能视为无要求", evidenceIds);
        }
        String requirement = "最低学历 " + job.minimumEducation();
        if (candidate.highestEducation() == EducationLevel.UNKNOWN) {
            return unknown(requirement, null, "候选人学历信息缺失", evidenceIds);
        }
        return rank(candidate.highestEducation()) >= rank(job.minimumEducation())
            ? pass(requirement, candidateFact, "学历满足最低要求", evidenceIds)
            : fail(requirement, candidateFact, "学历低于 " + job.minimumEducation(), evidenceIds);
    }

    RuleResult evaluateMajor(CandidateProfile candidate, JobPosting job, List<UUID> evidenceIds) {
        if (job.exactMajors().isEmpty()) return notApplicable("岗位未限定精确专业目录", evidenceIds);
        String requirement = String.join("、", job.exactMajors());
        if (candidate.majors().isEmpty()) return unknown(requirement, null, "候选人专业信息缺失", evidenceIds);
        String candidateFact = String.join("、", candidate.majors());
        Set<String> allowed = job.exactMajors().stream().map(EligibilityEvaluator::normalize).collect(Collectors.toSet());
        if (candidate.majors().stream().map(EligibilityEvaluator::normalize).anyMatch(allowed::contains)) {
            return pass(requirement, candidateFact, "专业名称与允许目录精确匹配", evidenceIds);
        }
        boolean taxonomyNeedsReview = job.exactMajors().stream()
            .anyMatch(value -> value.contains("门类") || value.endsWith("类") || value.contains("相关专业"));
        return taxonomyNeedsReview
            ? conditional(requirement, candidateFact,
                "岗位使用专业门类或宽泛目录，需要权威专业分类表或招录单位确认等同性", evidenceIds)
            : fail(requirement, candidateFact, "专业名称不在岗位允许目录中", evidenceIds);
    }

    RuleResult evaluateGraduation(CandidateProfile candidate, JobPosting job, List<UUID> evidenceIds) {
        if (job.acceptedGraduationYears().isEmpty()) return notApplicable("岗位无毕业届别限制", evidenceIds);
        String requirement = job.acceptedGraduationYears().stream().sorted()
            .map(String::valueOf).collect(Collectors.joining("、"));
        if (candidate.graduationYear() == null) return unknown(requirement, null, "候选人毕业年份缺失", evidenceIds);
        String candidateFact = String.valueOf(candidate.graduationYear());
        return job.acceptedGraduationYears().contains(candidate.graduationYear())
            ? pass(requirement, candidateFact, "毕业年份满足应届范围", evidenceIds)
            : fail(requirement, candidateFact, "毕业年份不在允许范围内", evidenceIds);
    }

    RuleResult evaluateExperience(CandidateProfile candidate, JobPosting job, List<UUID> evidenceIds) {
        if (job.minimumExperienceYears() == null || job.minimumExperienceYears() == 0) {
            return notApplicable("岗位无最低工作年限要求", evidenceIds);
        }
        String requirement = "不少于 " + job.minimumExperienceYears() + " 年";
        if (candidate.experienceYears() == null) return unknown(requirement, null, "候选人工作年限缺失", evidenceIds);
        String candidateFact = candidate.experienceYears() + " 年";
        return candidate.experienceYears() >= job.minimumExperienceYears()
            ? pass(requirement, candidateFact, "工作年限满足要求", evidenceIds)
            : fail(requirement, candidateFact, "工作年限不足", evidenceIds);
    }

    RuleResult evaluateProfessionalTitle(CandidateProfile candidate, JobPosting job, List<UUID> evidenceIds) {
        if (job.requiredProfessionalTitles().isEmpty()) return notApplicable("岗位无职称要求", evidenceIds);
        String requirement = String.join("、", job.requiredProfessionalTitles());
        if (candidate.professionalTitles().isEmpty()) {
            return fail(requirement, null, "缺少岗位要求的职称", evidenceIds);
        }
        String candidateFact = String.join("、", candidate.professionalTitles());
        Set<String> candidateTitles = candidate.professionalTitles().stream()
            .map(EligibilityEvaluator::normalize).collect(Collectors.toSet());
        return job.requiredProfessionalTitles().stream().map(EligibilityEvaluator::normalize).anyMatch(candidateTitles::contains)
            ? pass(requirement, candidateFact, "职称满足要求", evidenceIds)
            : fail(requirement, candidateFact, "职称不满足要求", evidenceIds);
    }

    private static String birthText(CandidateProfile candidate) {
        PartialDate birth = candidate.birthDate();
        return birth.day() == null
            ? birth.year() + "-" + String.format(Locale.ROOT, "%02d", birth.month())
            : birth.earliest().toString();
    }

    private static int rank(EducationLevel level) { return level.ordinal(); }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s·（）()_-]", "");
    }

    private static RuleResult pass(String requirement, String fact, String reason, List<UUID> evidenceIds) {
        return new RuleResult(CriterionStatus.PASS, requirement, fact, reason, evidenceIds);
    }
    private static RuleResult fail(String requirement, String fact, String reason, List<UUID> evidenceIds) {
        return new RuleResult(CriterionStatus.FAIL, requirement, fact, reason, evidenceIds);
    }
    private static RuleResult conditional(String requirement, String fact, String reason, List<UUID> evidenceIds) {
        return new RuleResult(CriterionStatus.CONDITIONAL, requirement, fact, reason, evidenceIds);
    }
    private static RuleResult unknown(String requirement, String fact, String reason, List<UUID> evidenceIds) {
        return new RuleResult(CriterionStatus.UNKNOWN, requirement, fact, reason, evidenceIds);
    }
    private static RuleResult notApplicable(String reason, List<UUID> evidenceIds) {
        return new RuleResult(CriterionStatus.NOT_APPLICABLE, null, null, reason, evidenceIds);
    }
}
