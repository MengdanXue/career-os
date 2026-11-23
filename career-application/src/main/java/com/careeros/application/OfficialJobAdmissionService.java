package com.careeros.application;

import static com.careeros.domain.DomainEnums.*;

import com.careeros.application.DecisionPorts.JobContexts;
import com.careeros.application.JobAdmissionPorts.JobAdmissions;
import com.careeros.application.JobAdmissionPorts.JobFieldEvidence;
import com.careeros.application.JobAdmissionPorts.FieldEvidenceCoverage;
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
    private final JobFieldEvidence fieldEvidence;

    public OfficialJobAdmissionService(JobContexts jobs, JobAdmissions admissions, JobFieldEvidence fieldEvidence) {
        this.jobs = Objects.requireNonNull(jobs);
        this.admissions = Objects.requireNonNull(admissions);
        this.fieldEvidence = Objects.requireNonNull(fieldEvidence);
    }

    public List<JobAdmission> classify(List<UUID> jobIds, Instant now) {
        Objects.requireNonNull(jobIds, "jobIds");
        Objects.requireNonNull(now, "now");
        var result = new ArrayList<JobAdmission>();
        var distinctJobIds = new LinkedHashSet<>(jobIds);
        var evidenceByJob = fieldEvidence.coverage(distinctJobIds);
        var contextsByJob = new java.util.LinkedHashMap<UUID, DecisionPorts.JobContext>();
        jobs.findActiveByJobIds(distinctJobIds).forEach(context ->
            contextsByJob.put(context.job().id(), context));
        var existingByJob = admissions.findByJobIds(distinctJobIds);
        var classifiedByJob = new java.util.LinkedHashMap<UUID, JobAdmission>();
        for (UUID jobId : distinctJobIds) {
            var context = contextsByJob.get(jobId);
            if (context == null) continue;
            var existing = existingByJob.get(jobId);
            if (existing != null && existing.humanVerified()) classifiedByJob.put(jobId, existing);
            else classifiedByJob.put(jobId, classify(
                context.job(), evidenceByJob.getOrDefault(context.job().id(),
                    new FieldEvidenceCoverage(Set.of(), Set.of(), false, false, false)), now));
        }
        var automated = classifiedByJob.values().stream().filter(value -> !value.humanVerified()).toList();
        var savedAutomated = admissions.saveAll(automated).stream()
            .collect(java.util.stream.Collectors.toMap(JobAdmission::jobPostingId, value -> value));
        distinctJobIds.forEach(jobId -> {
            var value = classifiedByJob.get(jobId);
            if (value != null) result.add(value.humanVerified() ? value : savedAutomated.get(jobId));
        });
        return List.copyOf(result);
    }

    /**
     * 按当前规则把全部在招岗位重新评估一遍，分批进行。
     *
     * <p>自动准入只在导入时运行。规则或证据读取更新之后，早先导入的岗位还停在旧版本的结论上——
     * 初筛队列因此是不完整的，证据其实已经齐全的岗位不会出现在复核人面前。
     * 人工做过的决定照旧保留（{@link #classify} 不覆盖 {@code humanVerified} 的行）。
     */
    public Reclassification reclassifyActive(Instant now, int batchSize) {
        Objects.requireNonNull(now, "now");
        if (batchSize < 1 || batchSize > 2000) throw new IllegalArgumentException("batchSize must be between 1 and 2000");
        var ids = jobs.findActive().stream().map(context -> context.job().id()).toList();
        var results = new ArrayList<JobAdmission>();
        for (int from = 0; from < ids.size(); from += batchSize) {
            results.addAll(classify(ids.subList(from, Math.min(from + batchSize, ids.size())), now));
        }
        return Reclassification.of(results);
    }

    /**
     * @param readyForReview 证据齐全、用工身份已知、目标技术岗、还没有人工决定——复核人可以直接纳入的数量
     */
    public record Reclassification(int processed, int humanVerified,
                                   java.util.Map<DataQualityStatus, Long> byQuality,
                                   java.util.Map<TargetScopeStatus, Long> byScope, int readyForReview) {
        static Reclassification of(List<JobAdmission> results) {
            var byQuality = new java.util.EnumMap<DataQualityStatus, Long>(DataQualityStatus.class);
            var byScope = new java.util.EnumMap<TargetScopeStatus, Long>(TargetScopeStatus.class);
            int human = 0, ready = 0;
            for (JobAdmission value : results) {
                byQuality.merge(value.dataQualityStatus(), 1L, Long::sum);
                byScope.merge(value.targetScopeStatus(), 1L, Long::sum);
                if (value.humanVerified()) { human++; continue; }
                if (value.dataQualityStatus() == DataQualityStatus.VERIFIED
                    && value.targetScopeStatus() == TargetScopeStatus.NEEDS_REVIEW
                    && value.reasonCodes().contains(JobAdmissionReason.TARGET_TECHNICAL_ROLE)
                    && !value.reasonCodes().contains(JobAdmissionReason.EMPLOYMENT_IDENTITY_UNKNOWN)) ready++;
            }
            return new Reclassification(results.size(), human, java.util.Map.copyOf(byQuality),
                java.util.Map.copyOf(byScope), ready);
        }
    }

    /**
     * 只按规则和证据给出的那一份自动结论，不看已有的人工决定。
     *
     * <p>人工确认范围时要拿它作底：数据质量、规则排除和原因码都来自这里，人只决定"在不在范围内"。
     * 若另写一份判定，人工那一侧就可能在证据不全时放行，和自动准入各判各的。
     */
    public static JobAdmission automatedAssessment(JobPosting job, FieldEvidenceCoverage evidence, Instant now) {
        return classify(job, evidence, now);
    }

    private static JobAdmission classify(JobPosting job, FieldEvidenceCoverage evidence, Instant now) {
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
        boolean hasEvidence = evidence.officialAttachment();
        boolean completeFieldEvidence = List.of("title", "organizationName", "headcount",
            "educationRequirementText", "majorRequirementText", "ageRequirementText")
            .stream().allMatch(evidence::explicit)
            && evidence.applicationDeadlineExplicit()
            && evidence.employmentIdentityExplicit()
            && evidence.conflictFields().isEmpty();
        boolean completeCriticalFacts = job.employmentType() != EmploymentType.UNKNOWN
            && job.minimumEducation() != EducationLevel.UNKNOWN
            && (!job.exactMajors().isEmpty() || evidence.notRequired("majorRequirementText"))
            && (job.maximumAge() != null || evidence.notRequired("ageRequirementText"))
            && completeFieldEvidence;
        if (!hasEvidence || !completeFieldEvidence) {
            reasons.add(JobAdmissionReason.MISSING_FIELD_EVIDENCE);
        }
        if (!completeCriticalFacts) {
            reasons.add(JobAdmissionReason.OFFICIAL_FACTS_INCOMPLETE);
        }
        // 冲突此前只体现为 REVIEW_REQUIRED，读的人看不出是"缺证据"还是"证据打架"，
        // 而这两者要做的事完全不同：前者去补，后者要先核对来源。
        if (!evidence.conflictFields().isEmpty()) {
            reasons.add(JobAdmissionReason.CONFLICTING_FIELD_EVIDENCE);
        }
        DataQualityStatus quality = !evidence.conflictFields().isEmpty()
            ? DataQualityStatus.REVIEW_REQUIRED
            : hasEvidence && completeCriticalFacts
                ? DataQualityStatus.VERIFIED
                : DataQualityStatus.NORMALIZED;
        return new JobAdmission(job.id(), quality, scope, reasons,
            "admission-v4-field-evidence", now, false);
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
