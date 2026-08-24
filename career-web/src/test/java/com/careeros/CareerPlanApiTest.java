package com.careeros;

import static com.careeros.domain.DomainEnums.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.careeros.application.planning.CareerPlanPorts.CareerPlanData;
import com.careeros.application.planning.CareerPlanService;
import com.careeros.domain.CandidateProfile;
import com.careeros.domain.EducationRecord;
import com.careeros.domain.PartialDate;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CareerPlanApiTest {
    private static final UUID CANDIDATE_ID = UUID.fromString("01992f09-0000-7000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");

    @Test
    void returnsPlannerSectionsAndRejectsTargetYearsOutsideTheFiveYearWindow() throws Exception {
        var candidate = new CandidateProfile(CANDIDATE_ID, "测试候选人", new PartialDate(1992, 12, 31),
            EducationLevel.BACHELOR, Set.of("计算机科学与技术"), 2014, null, Set.of("中级"), List.of("杭州"),
            Set.of(EmploymentType.ESTABLISHMENT), "profile-test", Set.of("Java"), Set.of(), Set.of(JobFamily.SOFTWARE),
            Set.of(OrganizationType.PUBLIC_INSTITUTION), List.of(
                new EducationRecord("示例海外大学", "示例国", EducationLevel.MASTER, "计算机科学", 2027, 6,
                    EducationRecord.CompletionStatus.EXPECTED, EducationRecord.CredentialVerificationStatus.PLANNED)));
        var service = new CareerPlanService((id, from, to, asOf) -> new CareerPlanData(candidate, List.of(), List.of(), NOW));
        var controller = new CareerPlanController(service, Clock.fixed(NOW, ZoneOffset.UTC));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();

        mvc.perform(get("/api/v1/candidates/{candidateId}/career-plan", CANDIDATE_ID)
                .param("targetYear", "2027").param("asOf", "2026-08-22"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentScenario.code").value("MASTER_IN_PROGRESS"))
            .andExpect(jsonPath("$.currentScenario.label").value("境外硕士在读"))
            .andExpect(jsonPath("$.graduateTrack.code").value("TARGET_YEAR_GRADUATE"))
            .andExpect(jsonPath("$.graduateTrack.outcome").value("CONDITIONALLY_ELIGIBLE"))
            .andExpect(jsonPath("$.recommendedRoutes").isArray())
            .andExpect(jsonPath("$.recommendedRoutes[0].scenarioBreakdowns").isArray())
            .andExpect(jsonPath("$.recommendedRoutes[0].scoreComponents[0].weight").value(30))
            .andExpect(jsonPath("$.futureScenarios[0].effectiveFrom").doesNotExist())
            .andExpect(jsonPath("$.ageWindows").isArray())
            .andExpect(jsonPath("$.qualificationRisks").isArray())
            .andExpect(jsonPath("$.dataCoverage.complete").value(false))
            .andExpect(jsonPath("$.algorithmVersion").value("career-plan-v3"));

        mvc.perform(get("/api/v1/candidates/{candidateId}/career-plan", CANDIDATE_ID)
                .param("targetYear", "2032").param("asOf", "2026-08-22"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("2026 至 2031")));
    }
}
