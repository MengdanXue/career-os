package com.careeros.infrastructure.persistence;

import static com.careeros.domain.DomainEnums.DataQualityStatus.RAW;
import static com.careeros.domain.DomainEnums.DataQualityStatus.VERIFIED;
import static com.careeros.domain.DomainEnums.JobAdmissionReason.TARGET_TECHNICAL_ROLE;
import static com.careeros.domain.DomainEnums.TargetScopeStatus.INCLUDED;
import static com.careeros.domain.DomainEnums.TargetScopeStatus.NEEDS_REVIEW;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.JobAdmissionPorts.JobAdmissions;
import com.careeros.domain.JobAdmission;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = JpaJobAdmissionStoreTest.TestApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class JpaJobAdmissionStoreTest {

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
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Test
    void readsTriggeredRawAdmissionAndPersistsVerifiedIncludedState(
        @Autowired JobAdmissions admissions,
        @Autowired JdbcTemplate jdbc
    ) {
        UUID jobId = insertJob(jdbc);

        JobAdmission raw = admissions.findByJobId(jobId).orElseThrow();
        assertThat(raw.dataQualityStatus()).isEqualTo(RAW);
        assertThat(raw.targetScopeStatus()).isEqualTo(NEEDS_REVIEW);
        assertThat(raw.admitted()).isFalse();

        JobAdmission verified = admissions.save(new JobAdmission(
            jobId, VERIFIED, INCLUDED, Set.of(TARGET_TECHNICAL_ROLE),
            "admission-v1", Instant.parse("2026-08-20T12:00:00Z"), true));

        assertThat(verified.admitted()).isTrue();
        assertThat(admissions.findByJobId(jobId)).contains(verified);
        assertThat(admissions.summarize().total()).isEqualTo(1);
        assertThat(admissions.summarize().count(VERIFIED)).isEqualTo(1);
        assertThat(admissions.summarize().count(INCLUDED)).isEqualTo(1);
        assertThat(admissions.summarize().opportunityReady()).isEqualTo(1);
    }

    private static UUID insertJob(JdbcTemplate jdbc) {
        UUID eventId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
            insert into recruitment_event (id, title, recruitment_year, event_type, source_url)
            values (?, 'event', 2026, 'PUBLIC_INSTITUTION', 'https://example.gov.cn/event')
            """, eventId);
        jdbc.update("""
            insert into organization (id, name, organization_type)
            values (?, 'organization', 'PUBLIC_INSTITUTION')
            """, organizationId);
        jdbc.update("""
            insert into job_posting
                (id, recruitment_event_id, organization_id, title, job_family,
                 minimum_education, source_url, content_fingerprint)
            values (?, ?, ?, 'job', 'SOFTWARE', 'BACHELOR', 'https://example.gov.cn/job', ?)
            """, jobId, eventId, organizationId, "a".repeat(64));
        return jobId;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.careeros.infrastructure.persistence")
    @EnableJpaRepositories(basePackages = "com.careeros.infrastructure.persistence")
    @Import(JpaJobAdmissionStore.class)
    static class TestApplication {}
}
