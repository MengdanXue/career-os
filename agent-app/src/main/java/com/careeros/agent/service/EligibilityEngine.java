package com.careeros.agent.service;

import com.careeros.agent.domain.CandidateProfile;
import com.careeros.agent.domain.CandidateProfile.Fact;
import com.careeros.agent.domain.CandidateProfile.FactStatus;
import com.careeros.agent.domain.EligibilityAssessment;
import com.careeros.agent.domain.EligibilityAssessment.Criterion;
import com.careeros.agent.domain.EligibilityAssessment.CriterionStatus;
import com.careeros.agent.domain.EligibilityAssessment.Evidence;
import com.careeros.agent.domain.EligibilityAssessment.OverallStatus;
import com.careeros.crawler.domain.NormalizedJob;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class EligibilityEngine {
    public EligibilityAssessment assess(CandidateProfile profile, NormalizedJob job) {
        List<Criterion> criteria = new ArrayList<>();
        criteria.add(checkEducation(profile, job));
        criteria.add(checkDegree(profile, job));
        criteria.add(checkMajor(profile, job));
        criteria.add(checkAge(profile, job));
        criteria.add(checkExperience(profile, job));
        criteria.add(checkPoliticalStatus(profile, job));
        criteria.add(checkProfessionalTitles(profile, job));
        criteria.add(checkApplicantType(profile, job));
        criteria.add(checkOtherConditions(job));

        OverallStatus overall = overall(criteria);
        List<String> confirmations = criteria.stream()
                .filter(item -> item.status() == CriterionStatus.UNKNOWN || item.status() == CriterionStatus.CONDITIONAL)
                .map(Criterion::reason)
                .distinct()
                .toList();
        return new EligibilityAssessment(job.jobId(), overall, criteria, confirmations);
    }

    private Criterion checkEducation(CandidateProfile profile, NormalizedJob job) {
        String required = job.requirements().education();
        if (blank(required)) return notApplicable("education", job, "公告未设置学历条件");
        return compareLevel("education", required, profile.education(), job);
    }

    private Criterion checkDegree(CandidateProfile profile, NormalizedJob job) {
        String required = job.requirements().degree();
        if (blank(required)) return notApplicable("degree", job, "公告未设置学位条件");
        return compareLevel("degree", required, profile.degree(), job);
    }

    private Criterion compareLevel(String code, String required, Fact fact, NormalizedJob job) {
        if (fact == null || blank(fact.value()) || fact.status() == FactStatus.UNKNOWN) {
            return criterion(code, CriterionStatus.UNKNOWN, required, describe(fact),
                    "候选人的" + code + "事实尚未确认", job);
        }
        int requiredLevel = educationLevel(required);
        int actualLevel = educationLevel(fact.value());
        if (requiredLevel > 0 && actualLevel > 0 && actualLevel < requiredLevel) {
            return criterion(code, CriterionStatus.FAIL, required, describe(fact),
                    "已确认层级低于岗位要求", job);
        }
        if (fact.status() == FactStatus.EXPECTED) {
            return criterion(code, CriterionStatus.CONDITIONAL, required, describe(fact),
                    "预计取得但尚未确认；还需核对公告规定的取得截止日期", job);
        }
        return criterion(code, CriterionStatus.PASS, required, describe(fact), "已确认满足层级要求", job);
    }

    private Criterion checkMajor(CandidateProfile profile, NormalizedJob job) {
        String required = job.requirements().majorText();
        if (blank(required)) return notApplicable("major", job, "公告未设置专业条件");
        Fact major = profile.major();
        if (major == null || blank(major.value()) || major.status() == FactStatus.UNKNOWN) {
            return criterion("major", CriterionStatus.UNKNOWN, required, describe(major),
                    "留服认证后的正式专业名称尚未确认", job);
        }
        if (!normalize(required).contains(normalize(major.value()))) {
            return criterion("major", CriterionStatus.UNKNOWN, required, describe(major),
                    "未发现精确专业名称命中；海外专业等同性必须由招录单位确认", job);
        }
        CriterionStatus status = major.status() == FactStatus.CONFIRMED
                ? CriterionStatus.PASS : CriterionStatus.CONDITIONAL;
        String reason = status == CriterionStatus.PASS
                ? "已确认专业名称在岗位专业原文中精确命中"
                : "目标专业命中，但留服认证正式名称尚未取得";
        return criterion("major", status, required, describe(major), reason, job);
    }

    private Criterion checkAge(CandidateProfile profile, NormalizedJob job) {
        NormalizedJob.Age required = job.requirements().age();
        if (required == null) return notApplicable("age", job, "公告未设置年龄条件");
        CandidateProfile.PartialDate birth = profile.birthDate();
        if (birth == null || birth.year() == null || birth.month() == null) {
            return criterion("age", CriterionStatus.UNKNOWN, required.rawText(), String.valueOf(birth),
                    "出生年月信息不足", job);
        }

        LocalDate earliest = LocalDate.of(birth.year(), birth.month(), birth.day() == null ? 1 : birth.day());
        LocalDate latest = birth.day() == null
                ? YearMonth.of(birth.year(), birth.month()).atEndOfMonth() : earliest;

        if (!blank(required.birthDateBoundary())) {
            LocalDate boundary = LocalDate.parse(required.birthDateBoundary());
            if (earliest.isAfter(boundary)) {
                return criterion("age", CriterionStatus.PASS, required.rawText(), birthText(birth),
                        "即使按该月最早出生日期计算也晚于公告出生日期边界", job);
            }
            if (!latest.isAfter(boundary)) {
                return criterion("age", CriterionStatus.FAIL, required.rawText(), birthText(birth),
                        "即使按该月最晚出生日期计算也不满足公告出生日期边界", job);
            }
            return criterion("age", CriterionStatus.UNKNOWN, required.rawText(), birthText(birth),
                    "出生日期边界落在出生月份内，需要精确出生日", job);
        }

        LocalDate deadline = parseDeadline(job.application().deadline());
        if (required.years() == null || deadline == null) {
            return criterion("age", CriterionStatus.UNKNOWN, required.rawText(), birthText(birth),
                    "缺少出生日期边界或可用于计算的报名截止日期", job);
        }
        int youngest = java.time.Period.between(latest, deadline).getYears();
        int oldest = java.time.Period.between(earliest, deadline).getYears();
        if (oldest <= required.years()) {
            return criterion("age", CriterionStatus.PASS, required.rawText(), birthText(birth),
                    "按出生月份年龄区间计算满足要求", job);
        }
        if (youngest > required.years()) {
            return criterion("age", CriterionStatus.FAIL, required.rawText(), birthText(birth),
                    "按出生月份年龄区间计算不满足要求", job);
        }
        return criterion("age", CriterionStatus.UNKNOWN, required.rawText(), birthText(birth),
                "年龄结果取决于精确出生日", job);
    }

    private Criterion checkExperience(CandidateProfile profile, NormalizedJob job) {
        Double required = job.requirements().experienceYearsMin();
        if (required == null) return notApplicable("experience", job, "公告未设置最低经历年限");
        CandidateProfile.NumericFact actual = profile.experienceYears();
        if (actual == null || actual.value() == null || actual.status() == FactStatus.UNKNOWN) {
            return criterion("experience", CriterionStatus.UNKNOWN, required + "年", String.valueOf(actual),
                    "相关工作年限尚未确认", job);
        }
        if (actual.value() < required) {
            return criterion("experience", CriterionStatus.FAIL, required + "年", actual.value() + "年",
                    "已确认经历年限不足", job);
        }
        return criterion("experience", CriterionStatus.PASS, required + "年", actual.value() + "年",
                "总年限满足；仍需逐岗核对相关经历口径", job);
    }

    private Criterion checkPoliticalStatus(CandidateProfile profile, NormalizedJob job) {
        String required = job.requirements().politicalStatus();
        if (blank(required)) return notApplicable("political_status", job, "公告未设置政治面貌条件");
        Fact actual = profile.politicalStatus();
        if (actual == null || blank(actual.value()) || actual.status() == FactStatus.UNKNOWN) {
            return criterion("political_status", CriterionStatus.UNKNOWN, required, describe(actual),
                    "政治面貌待本人确认", job);
        }
        CriterionStatus status = normalize(required).contains(normalize(actual.value()))
                ? CriterionStatus.PASS : CriterionStatus.FAIL;
        return criterion("political_status", status, required, describe(actual),
                status == CriterionStatus.PASS ? "政治面貌匹配" : "政治面貌不匹配", job);
    }

    private Criterion checkProfessionalTitles(CandidateProfile profile, NormalizedJob job) {
        List<String> required = job.requirements().certifications();
        if (required == null || required.isEmpty()) {
            return notApplicable("professional_title", job, "公告未设置职称或证书条件");
        }
        String requiredText = String.join("；", required);
        boolean matched = profile.professionalTitles().stream()
                .filter(item -> item.status() == FactStatus.CONFIRMED && !blank(item.value()))
                .anyMatch(item -> titleMatches(requiredText, item.value()));
        if (matched) {
            return criterion("professional_title", CriterionStatus.PASS, requiredText,
                    profile.professionalTitles().toString(), "已确认职称与要求存在明确层级命中", job);
        }
        return criterion("professional_title", CriterionStatus.UNKNOWN, requiredText,
                profile.professionalTitles().toString(), "证书/职称名称不能安全地自动判等，需要人工核对", job);
    }

    private Criterion checkApplicantType(CandidateProfile profile, NormalizedJob job) {
        String required = job.requirements().applicantType();
        if (blank(required)) return notApplicable("applicant_type", job, "公告未设置报考对象条件");
        Fact actual = profile.graduateStatus();
        if (required.contains("2026") && actual != null && actual.value() != null && actual.value().contains("2027")) {
            return criterion("applicant_type", CriterionStatus.FAIL, required, describe(actual),
                    "公告限定 2026 届，而候选人目标毕业年份为 2027", job);
        }
        if (required.contains("应届") || required.contains("毕业生")) {
            return criterion("applicant_type", CriterionStatus.CONDITIONAL, required, describe(actual),
                    "应届、留学人员和未落实工作单位口径需按完整公告确认", job);
        }
        return criterion("applicant_type", CriterionStatus.UNKNOWN, required, describe(actual),
                "报考对象条件尚未形成确定性规则", job);
    }

    private Criterion checkOtherConditions(NormalizedJob job) {
        List<String> conditions = job.requirements().otherConditions();
        if (conditions == null || conditions.isEmpty()) {
            return notApplicable("other_conditions", job, "没有未结构化的其他条件");
        }
        List<String> possibleHardConditions = conditions.stream()
                .filter(this::isPotentialHardCondition)
                .toList();
        if (possibleHardConditions.isEmpty()) {
            return notApplicable("other_conditions", job, "其他备注仅涉及考试或流程，不作为报考硬条件");
        }
        return criterion("other_conditions", CriterionStatus.UNKNOWN, String.join("；", possibleHardConditions), null,
                "存在尚未结构化的其他硬条件，需要人工复核", job);
    }

    private boolean isPotentialHardCondition(String condition) {
        if (blank(condition)) return false;
        if (condition.contains("面试") || condition.contains("笔试") || condition.contains("考试")
                || condition.contains("测试") || condition.contains("开考比例")) {
            return false;
        }
        return condition.matches(".*(须|要求|具有|持有|取得|仅限|不得|工作经历|党员|职称|资格|户籍|性别|社保|未落实|留服).*" );
    }

    private OverallStatus overall(List<Criterion> criteria) {
        if (criteria.stream().anyMatch(item -> item.status() == CriterionStatus.FAIL)) {
            return OverallStatus.INELIGIBLE;
        }
        if (criteria.stream().anyMatch(item -> item.status() == CriterionStatus.UNKNOWN)) {
            return OverallStatus.NEEDS_CONFIRMATION;
        }
        if (criteria.stream().anyMatch(item -> item.status() == CriterionStatus.CONDITIONAL)) {
            return OverallStatus.CONDITIONAL;
        }
        return OverallStatus.ELIGIBLE;
    }

    private Criterion notApplicable(String code, NormalizedJob job, String reason) {
        return criterion(code, CriterionStatus.NOT_APPLICABLE, null, null, reason, job);
    }

    private Criterion criterion(
            String code, CriterionStatus status, String requirement, String candidateFact,
            String reason, NormalizedJob job
    ) {
        return new Criterion(code, status, requirement, candidateFact, reason,
                new Evidence(job.source().announcementUrl(), job.source().evidenceText()));
    }

    private int educationLevel(String value) {
        if (blank(value)) return 0;
        if (value.contains("博士")) return 4;
        if (value.contains("硕士") || value.contains("研究生")) return 3;
        if (value.contains("本科") || value.contains("学士")) return 2;
        if (value.contains("专科") || value.contains("大专")) return 1;
        return 0;
    }

    private boolean titleMatches(String required, String actual) {
        String left = normalize(required);
        String right = normalize(actual);
        if (left.contains(right) || right.contains(left)) return true;
        return left.contains("中级") && right.contains("中级");
    }

    private LocalDate parseDeadline(String value) {
        if (blank(value)) return null;
        try {
            return OffsetDateTime.parse(value).toLocalDate();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(value.substring(0, Math.min(value.length(), 10)));
            } catch (DateTimeParseException | IndexOutOfBoundsException ignoredAgain) {
                return null;
            }
        }
    }

    private String birthText(CandidateProfile.PartialDate birth) {
        return birth.year() + "-" + String.format(Locale.ROOT, "%02d", birth.month())
                + (birth.day() == null ? "（日期待确认）" : "-" + String.format(Locale.ROOT, "%02d", birth.day()));
    }

    private String describe(Fact fact) {
        if (fact == null) return null;
        return String.valueOf(fact.value()) + " [" + fact.status() + "]"
                + (fact.expectedAt() == null ? "" : " expected_at=" + fact.expectedAt());
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\s、，,；;（）()]+", "").toLowerCase(Locale.ROOT);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
