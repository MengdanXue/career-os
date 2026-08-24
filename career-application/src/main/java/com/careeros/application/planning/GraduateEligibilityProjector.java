package com.careeros.application.planning;

import static com.careeros.application.planning.CareerPlan.QualificationOutcome.CONDITIONALLY_ELIGIBLE;
import static com.careeros.application.planning.CareerPlan.QualificationOutcome.ELIGIBLE;
import static com.careeros.application.planning.CareerPlan.QualificationOutcome.INELIGIBLE;
import static com.careeros.application.planning.CareerPlan.QualificationOutcome.UNCERTAIN;
import static com.careeros.domain.CandidateFacts.CandidateFactKey.EDUCATION_RECORDS;
import static com.careeros.domain.CandidateFacts.CandidateFactKey.EMPLOYMENT_HISTORY;
import static com.careeros.domain.EducationRecord.CompletionStatus.EXPECTED;
import static com.careeros.domain.EducationRecord.CredentialVerificationStatus.PLANNED;
import static com.careeros.domain.EducationRecord.CredentialVerificationStatus.UNKNOWN;
import static com.careeros.domain.GraduateEligibilityRule.EvidenceState.CONFIRMED;

import com.careeros.application.planning.CareerPlan.QualificationOutcome;
import com.careeros.domain.CandidateFacts;
import com.careeros.domain.CandidateProfile;
import com.careeros.domain.EducationRecord;
import com.careeros.domain.GraduateEligibilityRule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class GraduateEligibilityProjector {
    public enum EvaluationMode { HISTORICAL_ACTUAL, TARGET_YEAR_ANALOG }
    public enum GraduateTrack {
        TARGET_YEAR_GRADUATE, RECENT_GRADUATE_WINDOW, NOT_IN_GRADUATE_SCOPE, CONDITIONAL, UNKNOWN
    }

    public record GraduateTrackAssessment(
        GraduateTrack track,
        QualificationOutcome outcome,
        List<String> reasons
    ) {
        public GraduateTrackAssessment {
            Objects.requireNonNull(track);
            Objects.requireNonNull(outcome);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    public GraduateTrackAssessment assess(
        CandidateProfile candidate,
        CandidateFacts facts,
        GraduateEligibilityRule rule,
        EvaluationMode mode,
        int targetYear
    ) {
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(facts);
        Objects.requireNonNull(rule);
        Objects.requireNonNull(mode);
        if (targetYear < 2000 || targetYear > 2100) {
            throw new IllegalArgumentException("targetYear is invalid");
        }
        if (rule.evidenceState() != CONFIRMED) {
            return new GraduateTrackAssessment(GraduateTrack.UNKNOWN, UNCERTAIN,
                List.of("公告的毕业生范围尚未结构化核实"));
        }

        var education = targetEducation(candidate, targetYear);
        Integer graduationYear = education == null ? candidate.graduationYear() : education.graduationYear();
        if (graduationYear == null) {
            return new GraduateTrackAssessment(GraduateTrack.UNKNOWN, UNCERTAIN,
                List.of("候选人毕业年份未明确"));
        }

        int evaluationYear = mode == EvaluationMode.HISTORICAL_ACTUAL ? rule.recruitmentYear() : targetYear;
        Set<Integer> acceptedYears = mode == EvaluationMode.HISTORICAL_ACTUAL
            ? rule.explicitGraduationYears() : rule.acceptedYearsFor(targetYear);
        var reasons = new ArrayList<String>();
        if (!acceptedYears.contains(graduationYear)) {
            String reason = mode == EvaluationMode.HISTORICAL_ACTUAL
                ? graduationYear + " 届不属于 " + rule.recruitmentYear() + " 年公告的历史实际毕业生范围"
                : graduationYear + " 届不属于 " + targetYear + " 目标年度类比范围";
            return new GraduateTrackAssessment(GraduateTrack.NOT_IN_GRADUATE_SCOPE, INELIGIBLE,
                List.of(reason));
        }

        GraduateTrack track = graduationYear == evaluationYear
            ? GraduateTrack.TARGET_YEAR_GRADUATE : GraduateTrack.RECENT_GRADUATE_WINDOW;
        reasons.add(graduationYear == evaluationYear
            ? graduationYear + " 届属于目标年度当届毕业生范围"
            : graduationYear + " 届属于目标年度近届毕业生窗口");

        if (isOverseas(education) && !rule.includesOverseasGraduates()) {
            reasons.add("公告未将留学回国人员纳入该毕业生范围");
            return new GraduateTrackAssessment(track, INELIGIBLE, reasons);
        }
        if (!candidate.employmentRecords().isEmpty()) {
            if (rule.requiresNoEmployer()) {
                reasons.add("公告明示要求无劳动关系，已记录工作经历需要逐段核验");
                return new GraduateTrackAssessment(GraduateTrack.CONDITIONAL, CONDITIONALLY_ELIGIBLE, reasons);
            }
            reasons.add("公告未明示限制既往工作经历");
        }
        if (rule.restrictsSocialInsurance() && !facts.isConfirmed(EMPLOYMENT_HISTORY)) {
            reasons.add("社保限制已明示，但就业记录尚未核实");
            return new GraduateTrackAssessment(GraduateTrack.CONDITIONAL, CONDITIONALLY_ELIGIBLE, reasons);
        }
        if (!facts.isConfirmed(EDUCATION_RECORDS)) {
            reasons.add("学历记录尚未核实");
            return new GraduateTrackAssessment(GraduateTrack.CONDITIONAL, UNCERTAIN, reasons);
        }
        if (education != null && (education.completionStatus() == EXPECTED
            || education.credentialVerificationStatus() == PLANNED
            || education.credentialVerificationStatus() == UNKNOWN)) {
            reasons.add("硕士学位或境外学历认证尚待按公告时点完成");
            return new GraduateTrackAssessment(track, CONDITIONALLY_ELIGIBLE, reasons);
        }
        return new GraduateTrackAssessment(track, ELIGIBLE, reasons);
    }

    private static EducationRecord targetEducation(CandidateProfile candidate, int targetYear) {
        return candidate.educationRecords().stream()
            .filter(record -> record.graduationYear() != null)
            .sorted(Comparator
                .comparing((EducationRecord record) -> record.graduationYear() == targetYear).reversed()
                .thenComparing(record -> record.educationLevel().ordinal(), Comparator.reverseOrder()))
            .findFirst().orElse(null);
    }

    private static boolean isOverseas(EducationRecord education) {
        if (education == null || education.countryOrRegion() == null) return false;
        String country = education.countryOrRegion();
        return !(country.contains("中国") || country.contains("香港") || country.contains("澳门")
            || country.contains("台湾"));
    }
}
