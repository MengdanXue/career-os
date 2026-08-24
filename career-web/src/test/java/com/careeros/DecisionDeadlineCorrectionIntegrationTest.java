package com.careeros;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.DecisionIntelligenceService;
import com.careeros.application.JobAdmissionPorts.JobAdmissions;
import com.careeros.domain.JobAdmission;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "career-os.acquisition.scheduling-enabled=false")
@Testcontainers(disabledWithoutDocker = true)
class DecisionDeadlineCorrectionIntegrationTest {
    private static final UUID CANDIDATE_ID =
        UUID.fromString("01992f09-0000-7000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-24T15:27:31Z");

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

    @Test
    void aDeadlineCorrectionPersistsASecondCompleteDecisionSnapshot(
        @Autowired JdbcTemplate jdbc,
        @Autowired JobAdmissions admissions,
        @Autowired DecisionIntelligenceService decisions
    ) {
        UUID organizationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
            insert into organization
              (id, name, organization_type, administrative_level, province, city)
            values (?, '截止日回归测试单位', 'PUBLIC_INSTITUTION', '市级', '浙江', '杭州')
            """, organizationId);
        jdbc.update("""
            insert into recruitment_event
              (id, title, recruitment_year, event_type, published_on, application_ends_on,
               source_url, default_employment_type)
            values (?, '截止日回归测试公告', 2026, 'PUBLIC_INSTITUTION', ?, ?,
                    'https://example.gov.cn/deadline-regression', 'ESTABLISHMENT')
            """, eventId, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1));
        jdbc.update("""
            insert into job_posting
              (id, recruitment_event_id, organization_id, external_job_code, title, job_family,
               employment_type, location, headcount, minimum_education, source_url,
               stable_job_key, content_fingerprint)
            values (?, ?, ?, 'REG-1', '信息技术岗位', 'INFORMATION_SYSTEMS',
                    'ESTABLISHMENT', '杭州', 1, 'BACHELOR',
                    'https://example.gov.cn/deadline-regression', ?, ?)
            """, jobId, eventId, organizationId, "deadline-regression:" + jobId, "a".repeat(64));
        admissions.save(new JobAdmission(jobId, DataQualityStatus.VERIFIED,
            TargetScopeStatus.INCLUDED, Set.of(JobAdmissionReason.TARGET_TECHNICAL_ROLE),
            "integration-test", NOW, true));

        var first = decisions.assess(CANDIDATE_ID, jobId, NOW);
        jdbc.update("update recruitment_event set application_ends_on = ? where id = ?",
            LocalDate.of(2026, 9, 15), eventId);
        var corrected = decisions.assess(CANDIDATE_ID, jobId, NOW.plusSeconds(60));

        assertThat(corrected.decision().id()).isNotEqualTo(first.decision().id());
        assertThat(corrected.eligibility().evaluatorVersion())
            .isEqualTo(corrected.decision().evaluatorVersion());
        assertThat(corrected.fit().evaluatorVersion())
            .isEqualTo(corrected.decision().evaluatorVersion());
        assertThat(corrected.stability().evaluatorVersion())
            .isEqualTo(corrected.decision().evaluatorVersion());
        assertThat(jdbc.queryForObject(
            "select count(*) from eligibility_assessment where candidate_profile_id = ? and job_posting_id = ?",
            Integer.class, CANDIDATE_ID, jobId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
            "select count(*) from fit_assessment where candidate_profile_id = ? and job_posting_id = ?",
            Integer.class, CANDIDATE_ID, jobId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
            "select count(*) from stability_assessment where candidate_profile_id = ? and job_posting_id = ?",
            Integer.class, CANDIDATE_ID, jobId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
            "select count(*) from decision_assessment where candidate_profile_id = ? and job_posting_id = ?",
            Integer.class, CANDIDATE_ID, jobId)).isEqualTo(2);
    }
}
