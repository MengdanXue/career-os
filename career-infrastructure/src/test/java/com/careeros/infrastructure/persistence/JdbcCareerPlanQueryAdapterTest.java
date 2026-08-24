package com.careeros.infrastructure.persistence;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.RepositoryPorts;
import com.careeros.domain.CandidateProfile;
import com.careeros.domain.PartialDate;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcCareerPlanQueryAdapterTest {
    private static final UUID CANDIDATE_ID = UUID.fromString("01992f09-0000-7000-8000-000000000001");
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("career_os").withUsername("career_os").withPassword("career_os");
    static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        UUID event = UUID.fromString("30000000-0000-0000-0000-000000000001");
        jdbc.update("insert into recruitment_event(id,title,recruitment_year,event_type,published_on,application_starts_on,application_ends_on,written_exam_on,written_exam_subjects,graduate_rule,graduate_rule_json,written_exam_state,professional_test_state,interview_state,interview_on,interview_method,score_formula,source_url) values (?, '2026招聘', 2026, 'PUBLIC_INSTITUTION', '2026-03-20', '2026-03-25', '2026-03-31', '2026-04-25', '[\"职业能力倾向测验\"]'::jsonb, '2024、2025、2026届，含留学回国人员', ?::jsonb, 'CONFIRMED', 'NOT_REQUIRED', 'CONFIRMED', '2026-05-10', '结构化面试', '笔试、面试成绩各占50%', 'https://example.gov.cn/event')",
            event, "{\"recruitmentYear\":2026,\"explicitGraduationYears\":[2024,2025,2026],\"cohorts\":[\"CURRENT_YEAR\",\"PREVIOUS_YEAR\",\"TWO_YEARS_PRIOR\"],\"includesOverseasGraduates\":true,\"degreeTiming\":\"APPOINTMENT\",\"degreeDeadline\":\"2026-09-30\",\"credentialTiming\":\"APPOINTMENT\",\"credentialDeadline\":\"2026-09-30\",\"requiresNoEmployer\":false,\"restrictsSocialInsurance\":false,\"rawText\":\"2024、2025、2026届，含留学回国人员\",\"evidenceState\":\"CONFIRMED\"}");
        insertJob(event, "31000000-0000-0000-0000-000000000001", "杭州市信息中心", "PUBLIC_INSTITUTION", "信息技术", "SOFTWARE", "PUBLIC_INSTITUTION_FORMAL", "BACHELOR", "[\"计算机科学与技术\"]", "系统建设", 38);
        insertJob(event, "31000000-0000-0000-0000-000000000002", "浙江示例大学", "UNIVERSITY", "计算机教师", "RESEARCH", "PUBLIC_INSTITUTION_FORMAL", "DOCTORATE", "[\"计算机科学\"]", "教学科研", 35);
        insertJob(event, "31000000-0000-0000-0000-000000000003", "杭州数字中心", "PUBLIC_INSTITUTION", "软件开发", "SOFTWARE", "LABOR_DISPATCH", "BACHELOR", "[\"计算机科学\"]", "软件开发", 38);
        insertJob(event, "31000000-0000-0000-0000-000000000004", "杭州市医院", "HOSPITAL", "临床医师", "OTHER", "PUBLIC_INSTITUTION_FORMAL", "BACHELOR", "[\"临床医学\"]", "临床诊疗", 38);
        jdbc.update("update source_year_coverage set status='PARTIAL', updated_at=now() where recruitment_year=2026 and source_id=(select id from recruitment_source where code='HZ_HRSS_INSTITUTION')");
    }

    @Test
    void returnsOnlyTargetTechnicalRolesAndRetainsPartialCoverage() {
        var adapter = new JdbcCareerPlanQueryAdapter(jdbc, candidates());

        var data = adapter.load(CANDIDATE_ID, 2024, 2026, LocalDate.of(2026, 8, 22));

        assertThat(data.jobs()).extracting(job -> job.title()).containsExactly("信息技术");
        assertThat(data.jobs().getFirst().organizationName()).isEqualTo("杭州市信息中心");
        assertThat(data.jobs().getFirst().graduateEligibilityRule().acceptedYearsFor(2027))
            .containsExactlyInAnyOrder(2025, 2026, 2027);
        assertThat(data.jobs().getFirst().graduateRule()).contains("2026届", "留学回国人员");
        assertThat(data.jobs().getFirst().writtenExamState().name()).isEqualTo("CONFIRMED");
        assertThat(data.jobs().getFirst().professionalTestState().name()).isEqualTo("NOT_REQUIRED");
        assertThat(data.jobs().getFirst().interviewState().name()).isEqualTo("CONFIRMED");
        assertThat(data.jobs().getFirst().interviewOn()).isEqualTo(LocalDate.of(2026, 5, 10));
        assertThat(data.jobs().getFirst().interviewMethod()).isEqualTo("结构化面试");
        assertThat(data.jobs().getFirst().scoreFormula()).isEqualTo("笔试、面试成绩各占50%");
        assertThat(data.coverage()).anySatisfy(signal -> {
            assertThat(signal.sourceCode()).isEqualTo("HZ_HRSS_INSTITUTION");
            assertThat(signal.year()).isEqualTo(2026);
            assertThat(signal.status().name()).isEqualTo("PARTIAL");
        });
        assertThat(data.targetSources()).hasSizeGreaterThanOrEqualTo(20);
        assertThat(data.targetSources()).anyMatch(source -> source.routeCode().equals("RESEARCH_SUPPORT")
            && source.connectionStatus().name().equals("NOT_CONNECTED"));
        assertThat(data.targetSources()).filteredOn(source -> source.connectionStatus().name().equals("CONNECTED"))
            .extracting(source -> source.code())
            .containsExactlyInAnyOrder("ZJ_HRSS_INSTITUTION", "HZ_HRSS_INSTITUTION");
    }

    private static void insertJob(UUID event, String jobId, String organization, String organizationType, String title,
        String family, String employment, String education, String majors, String duties, int age) {
        UUID organizationId = UUID.nameUUIDFromBytes(organization.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("insert into organization(id,name,organization_type,province,city) values (?, ?, ?, '浙江', '杭州') on conflict (id) do nothing", organizationId, organization, organizationType);
        jdbc.update("insert into job_posting(id,recruitment_event_id,organization_id,title,job_family,employment_type,minimum_education,exact_majors,maximum_age,duties,source_url) values (?,?,?,?,?,?,?,?::jsonb,?,?,?)",
            UUID.fromString(jobId), event, organizationId, title, family, employment, education, majors, age, duties,
            "https://example.gov.cn/jobs/" + jobId);
    }

    private static RepositoryPorts.CandidateProfiles candidates() {
        CandidateProfile candidate = new CandidateProfile(CANDIDATE_ID, "测试候选人", new PartialDate(1992, 12, 31),
            EducationLevel.BACHELOR, Set.of("计算机科学与技术"), 2014, null, Set.of("中级"), List.of("杭州"),
            Set.of(EmploymentType.ESTABLISHMENT), "profile-test");
        return new RepositoryPorts.CandidateProfiles() {
            public CandidateProfile save(CandidateProfile value) { return value; }
            public Optional<CandidateProfile> findById(UUID id) { return id.equals(CANDIDATE_ID) ? Optional.of(candidate) : Optional.empty(); }
            public Optional<CandidateProfile> findByIdForUpdate(UUID id) { return findById(id); }
            public List<CandidateProfile> findAll() { return List.of(candidate); }
            public void deleteById(UUID id) {}
        };
    }
}
