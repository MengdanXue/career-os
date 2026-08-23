package com.careeros;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "career-os.acquisition.scheduling-enabled=false")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CareerPlanEndToEndTest {
    private static final UUID CANDIDATE_ID =
        UUID.fromString("01992f09-0000-7000-8000-000000000001");
    private static final UUID EVENT_ID =
        UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_ID =
        UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID JOB_ID =
        UUID.fromString("40000000-0000-0000-0000-000000000003");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("career_os")
        .withUsername("career_os")
        .withPassword("career_os");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void seedHistoricalJob(@Autowired JdbcTemplate jdbc) {
        jdbc.update("""
            insert into organization(id, name, organization_type, province, city)
            values (?, '杭州市政务信息中心', 'PUBLIC_INSTITUTION', '浙江', '杭州')
            on conflict (id) do nothing
            """, ORGANIZATION_ID);
        jdbc.update("""
            insert into recruitment_event(
                id, title, recruitment_year, event_type, published_on,
                application_starts_on, application_ends_on, written_exam_on,
                written_exam_subjects, source_url
            ) values (?, '杭州市事业单位2026年统一招聘', 2026, 'PUBLIC_INSTITUTION',
                '2026-03-10', '2026-03-20', '2026-03-27', '2026-04-25',
                '["职业能力倾向测验","综合应用能力"]'::jsonb,
                'https://example.gov.cn/2026-event')
            on conflict (id) do nothing
            """, EVENT_ID);
        jdbc.update("""
            insert into job_posting(
                id, recruitment_event_id, organization_id, title, job_family,
                employment_type, minimum_education, exact_majors, maximum_age,
                duties, original_requirement_text, source_url
            ) values (?, ?, ?, '信息系统建设', 'INFORMATION_SYSTEMS',
                'PUBLIC_INSTITUTION_FORMAL', 'BACHELOR', '["计算机科学与技术"]'::jsonb,
                35, '负责政务信息系统建设与运维', '本科，计算机科学与技术，35周岁以下',
                'https://example.gov.cn/jobs/information-systems')
            on conflict (id) do nothing
            """, JOB_ID, EVENT_ID, ORGANIZATION_ID);
    }

    @Test
    void buildsThePlannerFromSeededCandidateAndPersistedOfficialEvidence(
        @Autowired MockMvc mvc
    ) throws Exception {
        mvc.perform(get("/api/v1/candidates/{candidateId}/career-plan", CANDIDATE_ID)
                .param("targetYear", "2027")
                .param("asOf", "2026-08-22"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.candidateSnapshot.birthDate").value("1992-12-31"))
            .andExpect(jsonPath("$.candidateSnapshot.gender").value("FEMALE"))
            .andExpect(jsonPath("$.currentScenario.code").value("PRE_GRADUATION"))
            .andExpect(jsonPath("$.recommendedRoutes[0].code").value("PUBLIC_TECH"))
            .andExpect(jsonPath("$.recommendedRoutes[0].representativeJobs[0].jobId")
                .value(JOB_ID.toString()))
            .andExpect(jsonPath("$.historicalSummary[2].jobCount").value(1))
            .andExpect(jsonPath("$.recruitmentWindows[0].month").value(3))
            .andExpect(jsonPath("$.examPatterns[0].subject").exists())
            .andExpect(jsonPath("$.qualificationRisks[?(@.code == 'EMPLOYMENT_EVIDENCE')]").exists());
    }
}
