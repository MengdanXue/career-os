package com.careeros.application;

import static com.careeros.domain.DomainEnums.*;

import com.careeros.application.DecisionPorts.JobContexts;
import com.careeros.application.JobAdmissionPorts.JobAdmissions;
import com.careeros.domain.JobAdmission;
import com.careeros.domain.JobPosting;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class OfficialJobAdmissionService {
    private final JobContexts jobs;
    private final JobAdmissions admissions;

    public OfficialJobAdmissionService(JobContexts jobs, JobAdmissions admissions) {
        this.jobs = Objects.requireNonNull(jobs);
        this.admissions = Objects.requireNonNull(admissions);
    }

    public List<JobAdmission> classify(List<UUID> jobIds, Instant now) {
        Objects.requireNonNull(jobIds, "jobIds");
        Objects.requireNonNull(now, "now");
        var result = new ArrayList<JobAdmission>();
        for (UUID jobId : new LinkedHashSet<>(jobIds)) {
            jobs.findByJobId(jobId).filter(DecisionPorts.JobContext::active).ifPresent(context -> {
                var existing = admissions.findByJobId(jobId);
                if (existing.filter(JobAdmission::humanVerified).isPresent()) {
                    result.add(existing.orElseThrow());
                } else {
                    result.add(admissions.save(classify(context.job(), now)));
                }
            });
        }
        return List.copyOf(result);
    }

    private static JobAdmission classify(JobPosting job, Instant now) {
        Set<JobAdmissionReason> reasons = EnumSet.of(JobAdmissionReason.OFFICIAL_WORKBOOK_PARSED);
        TargetScopeStatus scope;
        JobAdmissionReason exclusion = exclusion(job);
        if (exclusion != null) {
            scope = TargetScopeStatus.EXCLUDED;
            reasons.add(exclusion);
        } else if (targetTechnical(job)) {
            scope = TargetScopeStatus.NEEDS_REVIEW;
            reasons.add(JobAdmissionReason.TARGET_TECHNICAL_ROLE);
        } else {
            scope = TargetScopeStatus.NEEDS_REVIEW;
            reasons.add(JobAdmissionReason.AMBIGUOUS_DUTIES);
        }
        if (job.employmentType() == EmploymentType.UNKNOWN) {
            reasons.add(JobAdmissionReason.EMPLOYMENT_IDENTITY_UNKNOWN);
        }
        return new JobAdmission(job.id(), DataQualityStatus.VERIFIED, scope, reasons,
            "admission-v2-official-workbook", now, false);
    }

    private static JobAdmissionReason exclusion(JobPosting job) {
        String text = text(job);
        if (job.employmentType() == EmploymentType.LABOR_DISPATCH) return JobAdmissionReason.LABOR_DISPATCH;
        if (job.employmentType() == EmploymentType.PROJECT_BASED) return JobAdmissionReason.PROJECT_BASED;
        if (job.minimumEducation() == EducationLevel.DOCTORATE) return JobAdmissionReason.DOCTOR_REQUIRED;
        if (text.contains("博士后")) return JobAdmissionReason.POSTDOCTORAL_ROLE;
        if (job.title().contains("教师") || job.title().contains("辅导员") || job.title().contains("教学岗")) {
            return JobAdmissionReason.TEACHING_ROLE;
        }
        if (text.contains("实习")) return JobAdmissionReason.INTERNSHIP;
        if (text.contains("销售") || text.contains("市场营销")) return JobAdmissionReason.SALES_ROLE;
        if ((job.title().contains("行政") || job.title().contains("文秘") || job.title().contains("党建")
                || job.title().contains("综合管理")) && !explicitTechnicalRole(job)) {
            return JobAdmissionReason.ADMINISTRATIVE_ROLE;
        }
        return null;
    }

    private static boolean targetTechnical(JobPosting job) {
        return explicitTechnicalRole(job);
    }

    private static boolean explicitTechnicalRole(JobPosting job) {
        String role = job.title() + " " + Objects.toString(job.duties(), "");
        return List.of("计算机", "软件", "人工智能", "机器学习", "算法", "大数据", "数据治理",
            "数据分析", "数据开发", "数据工程", "数据库", "信息化", "信息系统", "信息中心",
            "信息管理", "网络安全", "信息安全", "数字政府", "数字化", "系统运维", "技术支撑", "Java")
            .stream().anyMatch(role::contains);
    }

    private static String text(JobPosting job) {
        return job.title() + " " + Objects.toString(job.duties(), "") + " "
            + String.join(" ", job.exactMajors());
    }
}
