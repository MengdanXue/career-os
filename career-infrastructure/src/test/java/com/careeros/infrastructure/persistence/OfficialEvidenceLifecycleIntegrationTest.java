package com.careeros.infrastructure.persistence;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.infrastructure.acquisition.OfficialAnnouncementFactParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = OfficialEvidenceLifecycleIntegrationTest.TestApplication.class)
@Testcontainers(disabledWithoutDocker = true)
class OfficialEvidenceLifecycleIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("career_os").withUsername("career_os").withPassword("career_os");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Test
    void newWorkbookVersionRetiresOldFieldsAndDifferentSourceCreatesConflict(
        @Autowired OfficialWorkbookEvidenceService service,
        @Autowired RecruitmentEventJpaRepository events,
        @Autowired OrganizationJpaRepository organizations,
        @Autowired JobPostingJpaRepository jobs,
        @Autowired JdbcTemplate jdbc
    ) {
        var event = new JpaModels.RecruitmentEventEntity();
        event.id = UUID.randomUUID(); event.title = "岗位计划"; event.recruitmentYear = 2026;
        event.eventType = EventType.PUBLIC_INSTITUTION; event.sourceUrl = "https://example.gov.cn/notice";
        event.defaultEmploymentType = EmploymentType.UNKNOWN; event.evidenceIds = new ArrayList<>();
        events.save(event);
        var organization = new JpaModels.OrganizationEntity();
        organization.id = UUID.randomUUID(); organization.name = "测试单位";
        organization.organizationType = OrganizationType.PUBLIC_INSTITUTION;
        organizations.save(organization);
        var job = new JpaModels.JobPostingEntity();
        job.id = UUID.randomUUID(); job.recruitmentEventId = event.id; job.organizationId = organization.id;
        job.title = "信息中心岗"; job.jobFamily = JobFamily.INFORMATION_SYSTEMS;
        job.employmentType = EmploymentType.UNKNOWN; job.headcount = 1;
        job.minimumEducation = EducationLevel.BACHELOR; job.sourceUrl = event.sourceUrl;
        job.contentFingerprint = "a".repeat(64); job.active = true;
        job.firstSeenAt = Instant.parse("2026-08-01T00:00:00Z"); job.lastSeenAt = job.firstSeenAt;
        jobs.save(job);

        UUID first = service.begin(
            "https://example.gov.cn/download?fileName=jobs.xlsx&fileUrl=token-one",
            "岗位计划", "version-one".getBytes());
        service.replaceJobFacts(first, job.id, "岗位计划", 2, Map.of(
            "headcount", "1", "majorRequirementText", "不限", "ageRequirementText", "不限"));
        UUID second = service.begin(
            "https://example.gov.cn/download?fileName=jobs.xlsx&fileUrl=token-two",
            "岗位计划", "version-two".getBytes());
        service.replaceJobFacts(second, job.id, "岗位计划", 2, Map.of(
            "headcount", "2", "majorRequirementText", "不限", "ageRequirementText", "不限"));

        assertThat(jdbc.queryForObject(
            "select count(*) from job_field_evidence where job_posting_id=? and field_name='headcount'",
            Integer.class, job.id)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select normalized_value from job_field_evidence where job_posting_id=? and field_name='headcount'",
            String.class, job.id)).isEqualTo("2");
        assertThat(jdbc.queryForList(
            "select fact_status from job_field_evidence where job_posting_id=? and field_name in ('majorRequirementText','ageRequirementText')",
            String.class, job.id)).containsOnly("NOT_REQUIRED");

        UUID other = service.begin("https://other.example.gov.cn/other.xlsx", "另一来源", "other".getBytes());
        service.replaceJobFacts(other, job.id, "岗位计划", 2, Map.of("headcount", "3"));
        assertThat(jdbc.queryForList(
            "select distinct fact_status from job_field_evidence where job_posting_id=? and field_name='headcount'",
            String.class, job.id)).containsExactly("CONFLICT");
    }

    @Test
    void announcementExcerptsBecomeDeadlineAndEmploymentEvidence(
        @Autowired OfficialAnnouncementFactService announcementFacts,
        @Autowired JpaJobFieldEvidenceReader reader,
        @Autowired RecruitmentEventJpaRepository events,
        @Autowired OrganizationJpaRepository organizations,
        @Autowired JobPostingJpaRepository jobs,
        @Autowired JdbcTemplate jdbc
    ) {
        UUID evidenceId = UUID.randomUUID();
        String sourceUrl = "https://example.gov.cn/announcement/2026";
        jdbc.update("""
            insert into evidence
                (id, evidence_type, source_url, source_title, content_hash, captured_at)
            values (?, 'OFFICIAL_NOTICE', ?, '测试招聘公告', ?, ?)
            """, evidenceId, sourceUrl, "b".repeat(64),
            Timestamp.from(Instant.parse("2026-08-21T00:00:00Z")));
        var parsed = new OfficialAnnouncementFactParser().parse("""
            <html><body>
            <p>报名时间：2026年8月21日09:00—2026年8月28日17:00。</p>
            <p>本次招聘人员按规定签订聘用合同，纳入事业单位岗位管理。</p>
            </body></html>
            """, sourceUrl);
        var event = announcementFacts.upsert(
            "测试招聘公告", sourceUrl, 2026, EventType.PUBLIC_INSTITUTION, parsed, evidenceId);
        var workbookEvent = new JpaModels.RecruitmentEventEntity();
        workbookEvent.id = UUID.randomUUID();
        workbookEvent.title = event.title;
        workbookEvent.recruitmentYear = 2026;
        workbookEvent.eventType = EventType.PUBLIC_INSTITUTION;
        workbookEvent.sourceUrl = "https://example.gov.cn/attachment/jobs.xlsx";
        workbookEvent.defaultEmploymentType = EmploymentType.PUBLIC_INSTITUTION_FORMAL;
        workbookEvent.evidenceIds = new ArrayList<>();
        workbookEvent = events.save(workbookEvent);

        var organization = new JpaModels.OrganizationEntity();
        organization.id = UUID.randomUUID();
        organization.name = "公告测试单位";
        organization.organizationType = OrganizationType.PUBLIC_INSTITUTION;
        organizations.save(organization);
        var job = new JpaModels.JobPostingEntity();
        job.id = UUID.randomUUID();
        job.recruitmentEventId = workbookEvent.id;
        job.organizationId = organization.id;
        job.title = "信息化岗位";
        job.jobFamily = JobFamily.INFORMATION_SYSTEMS;
        job.employmentType = EmploymentType.PUBLIC_INSTITUTION_FORMAL;
        job.headcount = 1;
        job.minimumEducation = EducationLevel.BACHELOR;
        job.sourceUrl = sourceUrl;
        job.contentFingerprint = "c".repeat(64);
        job.active = true;
        job.evidenceIds = new ArrayList<>();
        job.firstSeenAt = Instant.parse("2026-08-21T00:00:00Z");
        job.lastSeenAt = job.firstSeenAt;
        jobs.save(job);

        var coverage = reader.coverage(job.id);
        assertThat(coverage.applicationDeadlineExplicit()).isTrue();
        assertThat(coverage.employmentIdentityExplicit()).isTrue();
        assertThat(coverage.evidenceReferences())
            .extracting(reference -> reference.fieldName())
            .contains("applicationEndsAt", "defaultEmploymentType");
        assertThat(coverage.evidenceReferences())
            .extracting(reference -> reference.excerpt())
            .anyMatch(excerpt -> excerpt.contains("报名时间"))
            .anyMatch(excerpt -> excerpt.contains("签订聘用合同"));

        UUID reparsedEvidenceId = UUID.randomUUID();
        jdbc.update("""
            insert into evidence
                (id, evidence_type, source_url, source_title, content_hash, captured_at)
            values (?, 'OFFICIAL_NOTICE', ?, '测试招聘公告更新', ?, ?)
            """, reparsedEvidenceId, sourceUrl, "e".repeat(64),
            Timestamp.from(Instant.parse("2026-08-22T00:00:00Z")));
        var partialReparse = new OfficialAnnouncementFactParser().parse("""
            <html><body><p>报名时间：2026年8月22日09:00—2026年8月29日17:00。</p></body></html>
            """, sourceUrl);
        var updated = announcementFacts.upsert(
            "测试招聘公告", sourceUrl, 2026, EventType.PUBLIC_INSTITUTION,
            partialReparse, reparsedEvidenceId);
        assertThat(updated.employmentStatement).contains("签订聘用合同");
        assertThat(updated.defaultEmploymentType).isEqualTo(EmploymentType.PUBLIC_INSTITUTION_FORMAL);
        assertThat(jdbc.queryForObject("""
            select count(*) from recruitment_event_field_evidence
            where recruitment_event_id=? and field_name='defaultEmploymentType'
            """, Integer.class, event.id)).isEqualTo(1);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.careeros.infrastructure.persistence")
    @EnableJpaRepositories(basePackages = "com.careeros.infrastructure.persistence")
    @Import({
        OfficialWorkbookEvidenceService.class,
        OfficialAnnouncementFactService.class,
        JpaJobFieldEvidenceReader.class
    })
    static class TestApplication {
        @Bean ObjectMapper objectMapper() {
            return JsonMapper.builder().findAndAddModules().build();
        }

        @Bean Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
