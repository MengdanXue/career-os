package com.careeros.agent.service;

import com.careeros.agent.config.CareerOsProperties;
import com.careeros.agent.domain.CandidateProfile;
import com.careeros.agent.domain.EligibilityAssessment;
import com.careeros.crawler.domain.NormalizedJob;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RealSampleEligibilityTest {
    @Test
    void evaluatesOfficialHangzhouHospitalRowWithoutTurningItMatchScoreIntoEligibility() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        Path jobsFile = Path.of("..", "..", "output", "career-os-samples", "actual", "spreadsheets", "jobs.json");
        List<NormalizedJob> jobs = mapper.readValue(jobsFile.toFile(), new TypeReference<>() {});
        NormalizedJob hospitalJob = jobs.stream()
                .filter(item -> item.jobId().equals("job_4423d798fa9335bd"))
                .findFirst()
                .orElseThrow();

        CandidateProfileRepository profiles = new CandidateProfileRepository(new CareerOsProperties(
                null, null, Path.of("..", "config", "candidate-profile.json")
        ));
        CandidateProfile profile = profiles.load();
        EligibilityAssessment result = new EligibilityEngine().assess(profile, hospitalJob);

        assertEquals("杭州市儿童医院", hospitalJob.employer().name());
        assertEquals("事业编制", hospitalJob.position().employmentType());
        assertEquals(EligibilityAssessment.OverallStatus.NEEDS_CONFIRMATION, result.status());
        assertEquals(EligibilityAssessment.CriterionStatus.CONDITIONAL, criterion(result, "degree").status());
        assertEquals(EligibilityAssessment.CriterionStatus.CONDITIONAL, criterion(result, "major").status());
        assertEquals(EligibilityAssessment.CriterionStatus.UNKNOWN, criterion(result, "age").status());
        assertEquals(EligibilityAssessment.CriterionStatus.NOT_APPLICABLE,
                criterion(result, "other_conditions").status());
    }

    private EligibilityAssessment.Criterion criterion(EligibilityAssessment result, String code) {
        return result.criteria().stream()
                .filter(item -> item.code().equals(code))
                .findFirst()
                .orElseThrow();
    }
}
