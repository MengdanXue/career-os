package com.careeros.domain;

import com.careeros.domain.DomainEnums.EducationLevel;
import com.careeros.domain.DomainEnums.EligibilityStatus;
import com.careeros.domain.DomainEnums.RuleType;
import com.careeros.domain.EligibilityAssessment.RuleResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class EligibilityEvaluator {
    public static final String VERSION = "phase0-rules-v1";

    public EligibilityAssessment evaluate(CandidateProfile candidate, JobPosting job) {
        return evaluate(candidate, job, Instant.now());
    }

    public EligibilityAssessment evaluate(CandidateProfile candidate, JobPosting job, Instant now) {
        return evaluate(candidate, job, "unversioned", now);
    }

    public EligibilityAssessment evaluate(CandidateProfile candidate, JobPosting job, String jobContentFingerprint, Instant now) {
        var results = new EnumMap<RuleType, RuleResult>(RuleType.class);
        results.put(RuleType.AGE, evaluateAge(candidate, job));
        results.put(RuleType.EDUCATION, evaluateEducation(candidate, job));
        results.put(RuleType.EXACT_MAJOR, evaluateMajor(candidate, job));
        results.put(RuleType.GRADUATE_YEAR, evaluateGraduation(candidate, job));
        results.put(RuleType.EXPERIENCE, evaluateExperience(candidate, job));
        results.put(RuleType.PROFESSIONAL_TITLE, evaluateProfessionalTitle(candidate, job));
        var overall = results.values().stream().map(RuleResult::status).max(EligibilityEvaluator::compareSeverity).orElse(EligibilityStatus.UNCERTAIN);
        return new EligibilityAssessment(UUID.randomUUID(), candidate.id(), job.id(), overall, results, job.evidenceIds(), VERSION, now, candidate.profileVersion(), jobContentFingerprint);
    }

    RuleResult evaluateAge(CandidateProfile candidate, JobPosting job) {
        if (job.maximumAge() == null) return eligible("岗位未设置最高年龄");
        if (job.ageReferenceDate() == null) return uncertain("岗位有年龄上限，但缺少年龄计算基准日");
        int youngest = Period.between(candidate.birthDate().latest(), job.ageReferenceDate()).getYears();
        int oldest = Period.between(candidate.birthDate().earliest(), job.ageReferenceDate()).getYears();
        if (oldest <= job.maximumAge()) return eligible("基准日年龄不超过 " + job.maximumAge() + " 周岁");
        if (youngest > job.maximumAge()) return ineligible("基准日年龄超过 " + job.maximumAge() + " 周岁");
        return uncertain("出生日期仅精确到月份，处于年龄边界，无法唯一判定");
    }

    RuleResult evaluateEducation(CandidateProfile candidate, JobPosting job) {
        if (job.minimumEducation() == EducationLevel.UNKNOWN) return uncertain("岗位最低学历信息缺失");
        if (candidate.highestEducation() == EducationLevel.UNKNOWN) return uncertain("候选人学历信息缺失");
        return rank(candidate.highestEducation()) >= rank(job.minimumEducation()) ? eligible("学历满足最低要求") : ineligible("学历低于 " + job.minimumEducation());
    }

    RuleResult evaluateMajor(CandidateProfile candidate, JobPosting job) {
        if (job.exactMajors().isEmpty()) return eligible("岗位未限定精确专业目录");
        if (candidate.majors().isEmpty()) return uncertain("候选人专业信息缺失");
        Set<String> allowed = job.exactMajors().stream().map(EligibilityEvaluator::normalize).collect(Collectors.toSet());
        boolean match = candidate.majors().stream().map(EligibilityEvaluator::normalize).anyMatch(allowed::contains);
        if (match) return eligible("专业名称与允许目录精确匹配");
        boolean taxonomyNeedsReview = job.exactMajors().stream().anyMatch(value -> value.contains("门类") || value.endsWith("类") || value.contains("相关专业"));
        return taxonomyNeedsReview ? uncertain("岗位使用专业门类或宽泛目录，需要权威专业分类表判定") : ineligible("专业名称不在岗位允许目录中");
    }

    RuleResult evaluateGraduation(CandidateProfile candidate, JobPosting job) {
        if (job.acceptedGraduationYears().isEmpty()) return eligible("岗位无毕业届别限制");
        if (candidate.graduationYear() == null) return uncertain("候选人毕业年份缺失");
        return job.acceptedGraduationYears().contains(candidate.graduationYear()) ? eligible("毕业年份满足应届范围") : ineligible("毕业年份不在允许范围内");
    }

    RuleResult evaluateExperience(CandidateProfile candidate, JobPosting job) {
        if (job.minimumExperienceYears() == null || job.minimumExperienceYears() == 0) return eligible("岗位无最低工作年限要求");
        if (candidate.experienceYears() == null) return uncertain("候选人工作年限缺失");
        return candidate.experienceYears() >= job.minimumExperienceYears() ? eligible("工作年限满足要求") : ineligible("工作年限不足 " + job.minimumExperienceYears() + " 年");
    }

    RuleResult evaluateProfessionalTitle(CandidateProfile candidate, JobPosting job) {
        if (job.requiredProfessionalTitles().isEmpty()) return eligible("岗位无职称要求");
        if (candidate.professionalTitles().isEmpty()) return ineligible("缺少岗位要求的职称");
        Set<String> candidateTitles = candidate.professionalTitles().stream().map(EligibilityEvaluator::normalize).collect(Collectors.toSet());
        boolean match = job.requiredProfessionalTitles().stream().map(EligibilityEvaluator::normalize).anyMatch(candidateTitles::contains);
        return match ? eligible("职称满足要求") : ineligible("职称不满足要求");
    }

    private static int rank(EducationLevel level) { return level.ordinal(); }
    private static String normalize(String value) { return Stream.of(value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s·（）()_-]", "")).findFirst().orElse(""); }
    private static int compareSeverity(EligibilityStatus left, EligibilityStatus right) { return Integer.compare(severity(left), severity(right)); }
    private static int severity(EligibilityStatus status) { return switch (status) { case ELIGIBLE -> 0; case LIKELY_ELIGIBLE -> 1; case UNCERTAIN -> 2; case LIKELY_INELIGIBLE -> 3; case INELIGIBLE -> 4; }; }
    private static RuleResult eligible(String message) { return new RuleResult(EligibilityStatus.ELIGIBLE, message); }
    private static RuleResult uncertain(String message) { return new RuleResult(EligibilityStatus.UNCERTAIN, message); }
    private static RuleResult ineligible(String message) { return new RuleResult(EligibilityStatus.INELIGIBLE, message); }
}
