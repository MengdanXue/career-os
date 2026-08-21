package com.careeros.infrastructure.persistence;

import com.careeros.application.JobAdmissionPorts.JobFieldEvidence;
import com.careeros.application.JobContentFingerprint;
import com.careeros.application.OfficialJobAdmissionService;
import com.careeros.domain.JobPosting;
import com.careeros.domain.DomainEnums.EmploymentType;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Propagates changed announcement-level eligibility facts even when an attachment is HTTP 304. */
@Service
public class OfficialAnnouncementJobRefreshService {
    private final JobPostingJpaRepository jobs;
    private final JobFieldEvidence fieldEvidence;
    private final OfficialJobAdmissionService admissions;
    private final Clock clock;

    public OfficialAnnouncementJobRefreshService(
        JobPostingJpaRepository jobs,
        JobFieldEvidence fieldEvidence,
        OfficialJobAdmissionService admissions,
        Clock clock
    ) {
        this.jobs = Objects.requireNonNull(jobs);
        this.fieldEvidence = Objects.requireNonNull(fieldEvidence);
        this.admissions = Objects.requireNonNull(admissions);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public void refresh(String announcementUrl, JpaModels.RecruitmentEventEntity announcement) {
        var related = jobs.findBySourceUrlAndActiveTrue(announcementUrl);
        if (related.isEmpty()) return;
        var ids = related.stream().map(value -> value.id).toList();
        var coverage = fieldEvidence.coverage(ids);
        for (var job : related) {
            var evidence = coverage.get(job.id);
            if (evidence == null || !evidence.explicit("employmentType")) {
                job.employmentType = announcement.defaultEmploymentType == null
                    ? EmploymentType.UNKNOWN : announcement.defaultEmploymentType;
            }
            if (job.ageRequirementText != null && !job.ageRequirementText.isBlank()) {
                job.ageReferenceDate = announcement.ageReferenceDate;
            }
            job.contentFingerprint = JobContentFingerprint.of(toDomain(job));
        }
        jobs.saveAllAndFlush(related);
        admissions.classify(ids, clock.instant());
    }

    private static JobPosting toDomain(JpaModels.JobPostingEntity job) {
        return new JobPosting(job.id,job.recruitmentEventId,job.organizationId,job.externalJobCode,job.title,
            job.jobFamily,job.employmentType,job.location,job.headcount,job.minimumEducation,
            job.exactMajors,job.acceptedGraduationYears,job.maximumAge,job.ageReferenceDate,
            job.minimumExperienceYears,job.requiredProfessionalTitles,job.duties,job.sourceUrl,
            job.evidenceIds == null ? java.util.List.of() : job.evidenceIds,job.supervisingDepartment,
            job.jobCategory,job.jobGrade,job.educationRequirementText,job.degreeRequirement,
            job.majorRequirementText,job.ageRequirementText,job.genderRequirement,job.candidateScope,
            job.otherRequirements,job.originalRequirementText,job.interviewRatio,
            job.professionalTestRequired,job.contactPhone);
    }
}
