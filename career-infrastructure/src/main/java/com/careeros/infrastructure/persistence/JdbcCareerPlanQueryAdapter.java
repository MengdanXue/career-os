package com.careeros.infrastructure.persistence;

import static com.careeros.application.planning.CareerPlanPorts.*;
import static com.careeros.domain.DomainEnums.*;

import com.careeros.application.RepositoryPorts;
import com.careeros.application.planning.CareerPlanService.CandidateNotFoundException;
import com.careeros.domain.CandidateFacts;
import com.careeros.domain.GraduateEligibilityRule;
import com.careeros.domain.GraduateEligibilityRule.EvidenceState;
import com.careeros.domain.acquisition.TargetSource;
import com.careeros.domain.acquisition.TargetSource.AuthorityLevel;
import com.careeros.domain.acquisition.TargetSource.ConnectionStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;

@Repository
public class JdbcCareerPlanQueryAdapter implements CareerPlanQuery {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<Integer>> INTEGER_LIST = new TypeReference<>() {};
    private static final String TARGET_JOBS_SQL = """
        select job.id job_id, event.id event_id, event.recruitment_year,
               event.published_on,
               coalesce(event.application_starts_on, event.application_starts_at::date) application_starts_on,
               coalesce(event.application_ends_on, event.application_ends_at::date) application_ends_on,
               event.written_exam_on, coalesce(job.age_reference_date, event.age_reference_date) age_reference_date,
               event.written_exam_subjects::text, organization.name organization_name,
               organization.organization_type, job.title, job.job_family, job.employment_type,
               job.minimum_education, job.maximum_age, job.minimum_experience_years,
               job.required_professional_titles::text, job.candidate_scope,
               job.exact_majors::text, job.accepted_graduation_years::text, job.gender_requirement,
               event.overseas_degree_rule, event.graduate_rule_json::text, event.graduate_rule,
               event.written_exam_state, event.professional_test_state, event.interview_state,
               event.interview_on, event.interview_method, event.score_formula,
               concat_ws('；', job.major_requirement_text, job.education_requirement_text,
                         job.age_requirement_text, job.other_requirements, job.original_requirement_text) requirements,
               job.source_url,
               (job.employment_type <> 'UNKNOWN'
                    and jsonb_array_length(job.exact_majors) > 0
                    and coalesce(job.original_requirement_text, job.major_requirement_text, job.duties, '') <> '') evidence_complete
        from job_posting job
        join recruitment_event event on event.id = job.recruitment_event_id
        join organization organization on organization.id = job.organization_id
        where event.recruitment_year between ? and ?
          and job.active
          and job.minimum_education in ('BACHELOR', 'MASTER')
          and job.employment_type not in ('LABOR_DISPATCH', 'PROJECT_BASED')
          and (
              job.exact_majors::text ~* '(计算机|软件|网络|数据|人工智能|电子信息)'
              or coalesce(job.major_requirement_text, '') ~ '(计算机|软件|网络|数据|人工智能|电子信息)'
          )
          and (
              job.job_family in ('SOFTWARE','DATA','AI','CYBERSECURITY','INFORMATION_SYSTEMS','DIGITALIZATION','IT_OPERATIONS','RESEARCH')
              or concat_ws(' ', job.title, job.duties) ~* '(信息|软件|数据|网络|系统|数字化|计算机|运维|技术)'
          )
          and concat_ws(' ', job.title, job.duties, job.other_requirements) !~* '(博士后|教师|教学|临床|护理|销售)'
        order by event.recruitment_year, event.id, job.id
        """;

    private static final String COVERAGE_SQL = """
        select source.code, coverage.recruitment_year, coverage.status, coverage.updated_at
        from source_year_coverage coverage
        join recruitment_source source on source.id = coverage.source_id
        where coverage.recruitment_year between ? and ?
        order by coverage.recruitment_year, source.code
        """;

    private static final String TARGET_SOURCES_SQL = """
        select code, name, route_code, region, authority_level, connection_status, official_root_url
        from target_source_catalog
        where enabled
        order by route_code, code
        """;

    private final JdbcTemplate jdbc;
    private final RepositoryPorts.CandidateProfiles candidates;
    private final RepositoryPorts.CandidateFactConfirmations confirmations;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public JdbcCareerPlanQueryAdapter(JdbcTemplate jdbc, RepositoryPorts.CandidateProfiles candidates) {
        this(jdbc, candidates, null);
    }

    @Autowired
    public JdbcCareerPlanQueryAdapter(JdbcTemplate jdbc, RepositoryPorts.CandidateProfiles candidates,
        RepositoryPorts.CandidateFactConfirmations confirmations) {
        this.jdbc = jdbc;
        this.candidates = candidates;
        this.confirmations = confirmations;
    }

    @Override
    public CareerPlanData load(UUID candidateId, int fromYear, int toYear, LocalDate asOf) {
        var candidate = candidates.findById(candidateId)
            .orElseThrow(() -> new CandidateNotFoundException("Candidate not found: " + candidateId));
        var failedSections = new ArrayList<String>();
        List<HistoricalJob> jobs;
        try {
            jobs = jdbc.query(TARGET_JOBS_SQL, this::mapJob, fromYear, toYear);
        } catch (DataAccessException exception) {
            jobs = List.of();
            failedSections.add("HISTORY");
        }
        List<CoverageSignal> coverage;
        try {
            coverage = jdbc.query(COVERAGE_SQL, this::mapCoverage, fromYear, toYear);
        } catch (DataAccessException exception) {
            coverage = List.of();
            failedSections.add("COVERAGE");
        }
        List<TargetSource> targetSources;
        try {
            targetSources = jdbc.query(TARGET_SOURCES_SQL, this::mapTargetSource);
        } catch (DataAccessException exception) {
            targetSources = List.of();
            failedSections.add("TARGET_SOURCES");
        }
        Instant loadedAt = coverage.stream().map(CoverageSignal::updatedAt).max(Instant::compareTo)
            .orElse(asOf.atStartOfDay().toInstant(ZoneOffset.UTC));
        var facts = confirmations == null ? CandidateFacts.resolve(candidate, List.of())
            : CandidateFacts.resolve(candidate, confirmations.findByCandidateId(candidateId));
        return new CareerPlanData(candidate, jobs, coverage, loadedAt, failedSections, facts, targetSources);
    }

    private HistoricalJob mapJob(ResultSet rows, int rowNumber) throws SQLException {
        return new HistoricalJob(
            rows.getObject("job_id", UUID.class), rows.getObject("event_id", UUID.class),
            rows.getInt("recruitment_year"), rows.getObject("published_on", LocalDate.class),
            rows.getObject("application_starts_on", LocalDate.class), rows.getObject("application_ends_on", LocalDate.class),
            rows.getObject("written_exam_on", LocalDate.class), rows.getObject("age_reference_date", LocalDate.class),
            strings(rows.getString("written_exam_subjects")), rows.getString("organization_name"),
            OrganizationType.valueOf(rows.getString("organization_type")), rows.getString("title"),
            JobFamily.valueOf(rows.getString("job_family")), EmploymentType.valueOf(rows.getString("employment_type")),
            EducationLevel.valueOf(rows.getString("minimum_education")), integer(rows, "maximum_age"),
            integer(rows, "minimum_experience_years"), new LinkedHashSet<>(strings(rows.getString("required_professional_titles"))),
            rows.getString("candidate_scope"), rows.getString("requirements"), rows.getString("source_url"),
            rows.getBoolean("evidence_complete"), strings(rows.getString("exact_majors")),
            integers(rows.getString("accepted_graduation_years")), rows.getString("gender_requirement"),
            rows.getString("overseas_degree_rule"), graduateRule(rows.getString("graduate_rule_json")),
            rows.getString("graduate_rule"), EvidenceState.valueOf(rows.getString("written_exam_state")),
            EvidenceState.valueOf(rows.getString("professional_test_state")),
            EvidenceState.valueOf(rows.getString("interview_state")),
            rows.getObject("interview_on", LocalDate.class), rows.getString("interview_method"),
            rows.getString("score_formula"));
    }

    private CoverageSignal mapCoverage(ResultSet rows, int rowNumber) throws SQLException {
        return new CoverageSignal(rows.getString("code"), rows.getInt("recruitment_year"),
            CoverageStatus.valueOf(rows.getString("status")), rows.getTimestamp("updated_at").toInstant());
    }

    private TargetSource mapTargetSource(ResultSet rows, int rowNumber) throws SQLException {
        return new TargetSource(rows.getString("code"), rows.getString("name"), rows.getString("route_code"),
            rows.getString("region"), AuthorityLevel.valueOf(rows.getString("authority_level")),
            ConnectionStatus.valueOf(rows.getString("connection_status")), rows.getString("official_root_url"));
    }

    private List<String> strings(String json) throws SQLException {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, STRING_LIST);
        } catch (Exception exception) {
            throw new SQLException("Invalid JSON array in career planning projection", exception);
        }
    }

    private List<Integer> integers(String json) throws SQLException {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, INTEGER_LIST);
        } catch (Exception exception) {
            throw new SQLException("Invalid JSON integer array in career planning projection", exception);
        }
    }

    private GraduateEligibilityRule graduateRule(String json) throws SQLException {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, GraduateEligibilityRule.class);
        } catch (Exception exception) {
            throw new SQLException("Invalid graduate eligibility rule in career planning projection", exception);
        }
    }

    private static Integer integer(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }
}
