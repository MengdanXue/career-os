package com.careeros.domain;

import com.careeros.domain.GraduateEligibilityRule.EvidenceState;
import java.util.Objects;

/** Evidence state for every recruitment stage; missing data is never treated as a confirmed absence. */
public record RecruitmentProcessFacts(
    ProcessStage notice,
    ProcessStage application,
    ProcessStage qualificationReview,
    ProcessStage payment,
    ProcessStage admissionTicket,
    ProcessStage writtenExam,
    ProcessStage professionalTest,
    ProcessStage interview,
    ProcessStage physicalExam,
    ProcessStage investigation,
    ProcessStage publication,
    ProcessStage appointment
) {
    public RecruitmentProcessFacts {
        notice = safe(notice);
        application = safe(application);
        qualificationReview = safe(qualificationReview);
        payment = safe(payment);
        admissionTicket = safe(admissionTicket);
        writtenExam = safe(writtenExam);
        professionalTest = safe(professionalTest);
        interview = safe(interview);
        physicalExam = safe(physicalExam);
        investigation = safe(investigation);
        publication = safe(publication);
        appointment = safe(appointment);
    }

    public static RecruitmentProcessFacts unknown() {
        var unknown = new ProcessStage(EvidenceState.UNKNOWN, null);
        return new RecruitmentProcessFacts(unknown, unknown, unknown, unknown, unknown, unknown,
            unknown, unknown, unknown, unknown, unknown, unknown);
    }

    public static RecruitmentProcessFacts fromLegacy(
        EvidenceState writtenExam,
        EvidenceState professionalTest,
        EvidenceState interview
    ) {
        var value = unknown();
        return new RecruitmentProcessFacts(value.notice, value.application, value.qualificationReview,
            value.payment, value.admissionTicket, new ProcessStage(writtenExam, null),
            new ProcessStage(professionalTest, null), new ProcessStage(interview, null),
            value.physicalExam, value.investigation, value.publication, value.appointment);
    }

    private static ProcessStage safe(ProcessStage value) {
        return value == null ? new ProcessStage(EvidenceState.UNKNOWN, null) : value;
    }

    public record ProcessStage(EvidenceState state, String detail) {
        public ProcessStage {
            state = Objects.requireNonNullElse(state, EvidenceState.UNKNOWN);
            detail = detail == null || detail.isBlank() ? null : detail.strip();
        }
    }
}
