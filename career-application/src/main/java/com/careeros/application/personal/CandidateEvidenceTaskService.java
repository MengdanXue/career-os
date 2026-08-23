package com.careeros.application.personal;

import static com.careeros.application.personal.CandidateEvidenceTask.EvidenceStrength.NONE;
import static com.careeros.application.personal.CandidateEvidenceTask.EvidenceStrength.SELF_REPORTED;
import static com.careeros.application.personal.CandidateEvidenceTask.EvidenceTaskKind.*;
import static com.careeros.domain.CandidateFacts.CandidateFactKey.*;
import static com.careeros.domain.CandidateFacts.CandidateFactStatus.CONFIRMED;

import com.careeros.application.personal.CandidateEvidenceTask.EvidenceStrength;
import com.careeros.application.personal.CandidateEvidenceTask.EvidenceTaskKind;
import com.careeros.application.personal.CandidateEvidenceTaskPorts.CandidateFactsSnapshot;
import com.careeros.application.personal.CandidateEvidenceTaskPorts.CandidateSnapshot;
import com.careeros.application.personal.CandidateEvidenceTaskPorts.CandidateQualificationImpact;
import com.careeros.application.personal.CandidateEvidenceTaskPorts.QualificationImpact;
import com.careeros.domain.CandidateEmploymentRecord.VerificationStatus;
import com.careeros.domain.CandidateFacts.CandidateFactKey;
import com.careeros.domain.EducationRecord;
import com.careeros.domain.EducationRecord.CompletionStatus;
import com.careeros.domain.EducationRecord.CredentialVerificationStatus;
import com.careeros.domain.DomainEnums.EducationLevel;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class CandidateEvidenceTaskService {
    private static final Map<EvidenceTaskKind, Integer> KIND_PRIORITY = Map.of(
        EMPLOYMENT, 0,
        GRADUATION, 1,
        CREDENTIAL, 2,
        SKILL, 3,
        RESEARCH, 4,
        EvidenceTaskKind.POLITICAL_AFFILIATION, 5,
        PROFESSIONAL_TITLE, 6
    );

    private final CandidateFactsSnapshot facts;
    private final CandidateQualificationImpact impact;

    public CandidateEvidenceTaskService(CandidateFactsSnapshot facts, CandidateQualificationImpact impact) {
        this.facts = Objects.requireNonNull(facts);
        this.impact = Objects.requireNonNull(impact);
    }

    public CandidateEvidenceTasks tasks(UUID candidateId, LocalDate asOf) {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(asOf, "asOf");
        CandidateSnapshot snapshot;
        try {
            snapshot = facts.load(candidateId);
        } catch (com.careeros.application.CandidateProfileService.CandidateProfileNotFoundException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return new CandidateEvidenceTasks(candidateId, asOf, false, "候选人资料暂时无法读取", List.of());
        }

        QualificationImpact qualificationImpact;
        boolean available = true;
        String message = null;
        try {
            qualificationImpact = impact.load(candidateId, asOf);
        } catch (RuntimeException exception) {
            qualificationImpact = new QualificationImpact(Map.of());
            available = false;
            message = "目标岗位影响暂时无法计算";
        }

        var tasks = derive(snapshot, qualificationImpact);
        tasks.sort(Comparator
            .comparingInt(CandidateEvidenceTask::affectedJobCount).reversed()
            .thenComparingInt(task -> KIND_PRIORITY.get(task.kind()))
            .thenComparing(CandidateEvidenceTask::code));
        return new CandidateEvidenceTasks(candidateId, asOf, available, message, tasks);
    }

    private static List<CandidateEvidenceTask> derive(
        CandidateSnapshot snapshot,
        QualificationImpact impact
    ) {
        var profile = snapshot.profile();
        var tasks = new ArrayList<CandidateEvidenceTask>();

        boolean hasVerifiedEmployment = profile.employmentRecords().stream()
            .anyMatch(record -> record.verificationStatus() == VerificationStatus.VERIFIED);
        boolean hasIncompleteEmployment = profile.employmentRecords().stream()
            .anyMatch(record -> record.verificationStatus() != VerificationStatus.VERIFIED
                && record.verificationStatus() != VerificationStatus.REJECTED);
        boolean confirmedEmptyEmployment = snapshot.status(EMPLOYMENT_HISTORY) == CONFIRMED
            && profile.employmentRecords().isEmpty();
        if (!confirmedEmptyEmployment
            && (!hasVerifiedEmployment || hasIncompleteEmployment || snapshot.status(EMPLOYMENT_HISTORY) != CONFIRMED)) {
            tasks.add(task("VERIFY_EMPLOYMENT_HISTORY", EMPLOYMENT, EMPLOYMENT_HISTORY,
                "补齐可核验工作经历",
                legacyExperienceReason(profile.experienceYears()), impact, NONE, "employment-history"));
        }

        if (snapshot.status(CandidateFactKey.POLITICAL_AFFILIATION) != CONFIRMED
            || profile.politicalAffiliation() == com.careeros.domain.DomainEnums.PoliticalAffiliation.UNKNOWN) {
            tasks.add(task("CONFIRM_POLITICAL_AFFILIATION", EvidenceTaskKind.POLITICAL_AFFILIATION,
                CandidateFactKey.POLITICAL_AFFILIATION,
                "确认政治面貌", "当前值没有被证据状态确认，相关岗位必须按未知处理。",
                impact, SELF_REPORTED, "political-affiliation"));
        }

        for (EducationRecord record : profile.educationRecords()) {
            if (record.educationLevel() != EducationLevel.MASTER) continue;
            if (record.completionStatus() == CompletionStatus.EXPECTED && record.graduationMonth() == null) {
                tasks.add(task("CONFIRM_MASTER_GRADUATION_MONTH", GRADUATION, EDUCATION_RECORDS,
                    "确认硕士预计毕业月份", "招聘公告常按毕业月份或学位取得时间判断应届资格。",
                    impact, SELF_REPORTED, "master-graduation"));
            }
            if (isOverseas(record) && record.credentialVerificationStatus() != CredentialVerificationStatus.VERIFIED) {
                tasks.add(task("VERIFY_MASTER_CREDENTIAL", CREDENTIAL, EDUCATION_RECORDS,
                    "跟进海外学历认证证据", "需要记录留服认证状态、正式专业名称和完成时间，才能稳定判断学历条件。",
                    impact, SELF_REPORTED, "credential-verification"));
            }
        }

        if (snapshot.status(PROFESSIONAL_TITLES) != CONFIRMED) {
            tasks.add(task("VERIFY_PROFESSIONAL_TITLE", PROFESSIONAL_TITLE, PROFESSIONAL_TITLES,
                "核验专业职称", "职称要求属于硬条件，需要证书或评审材料支持。",
                impact, SELF_REPORTED, "professional-title"));
        }
        if (snapshot.status(SKILLS) != CONFIRMED) {
            tasks.add(task("ADD_SKILL_EVIDENCE", SKILL, SKILLS,
                "补充可核验技能证据", "技能关键词尚未确认，需要用项目、代码或工作材料支持。",
                impact, NONE, "skill-evidence"));
        }
        if (snapshot.status(RESEARCH_KEYWORDS) != CONFIRMED) {
            tasks.add(task("ADD_RESEARCH_EVIDENCE", RESEARCH, RESEARCH_KEYWORDS,
                "补充研究与项目证据", "研究方向尚未确认，无法可靠判断高校和研究岗位的软匹配。",
                impact, NONE, "research-evidence"));
        }
        return tasks;
    }

    private static CandidateEvidenceTask task(
        String code,
        EvidenceTaskKind kind,
        CandidateFactKey factKey,
        String title,
        String reason,
        QualificationImpact impact,
        EvidenceStrength strength,
        String anchor
    ) {
        return new CandidateEvidenceTask(code, kind, factKey, title, reason, impact.count(factKey),
            strength, "/profile#" + anchor);
    }

    private static String legacyExperienceReason(Integer experienceYears) {
        if (experienceYears == null) {
            return "尚无逐段核验的工作经历，工作年限类岗位必须按未知处理。";
        }
        return "旧资料记录 " + experienceYears + " 年，但硬资格只能使用逐段核验的工作经历。";
    }

    private static boolean isOverseas(EducationRecord record) {
        String region = record.countryOrRegion();
        return region != null && !region.equalsIgnoreCase("中国") && !region.equalsIgnoreCase("China");
    }

    public record CandidateEvidenceTasks(
        UUID candidateId,
        LocalDate asOf,
        boolean available,
        String message,
        List<CandidateEvidenceTask> items
    ) {
        public CandidateEvidenceTasks {
            Objects.requireNonNull(candidateId);
            Objects.requireNonNull(asOf);
            items = List.copyOf(items);
        }
    }
}
