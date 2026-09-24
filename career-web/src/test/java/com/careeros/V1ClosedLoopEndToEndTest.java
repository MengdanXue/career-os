package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.careeros.application.OfficialJobAdmissionService;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * v1.0 的最小闭环，在真实 PostgreSQL 与完整应用上下文里走一遍：
 *
 * <ol>
 *   <li>一个带齐官方字段证据的目标技术岗进库，自动准入把它判为数据质量 VERIFIED、范围 NEEDS_REVIEW——
 *       此时可信池里没有它；</li>
 *   <li>复核人经接口把它纳入可信池（带内容指纹；过期指纹被拒）；</li>
 *   <li>资格与分层对它生效；</li>
 *   <li>Agent 经 {@code ModelPlanner} 的解析路径（录制的模型输出）查岗位、看详情、按模板收尾；</li>
 *   <li>整次运行落台账：规划器身份、每一步、每一步引用的官方证据、每一轮模型原文。</li>
 * </ol>
 *
 * <p>采集与解析各自有测试；这里的岗位与证据用 SQL 放进去，形状与导入服务写入的一致。
 * 录制的模型输出<b>不是模型能力的证据</b>——它证明的是这条路径接通了。真实模型的样本在
 * {@code scripts/v1_closed_loop.py} 与 {@code ModelPlannerLiveSampleTest} 里跑。
 */
@SpringBootTest(properties = "career-os.acquisition.scheduling-enabled=false")
@AutoConfigureMockMvc
@Import(TestSecurityDefaults.class)
@Testcontainers(disabledWithoutDocker = true)
class V1ClosedLoopEndToEndTest {
    private static final UUID CANDIDATE = UUID.fromString("01992f09-0000-7000-8000-000000000001");
    private static final UUID JOB = UUID.fromString("01992f09-0000-7000-8000-00000000c10b");
    private static final String FINGERPRINT = "c".repeat(64);
    private static final String ANNOUNCEMENT = "https://hrss.hangzhou.gov.cn/art/2026/9/1/v1-closed-loop.html";
    private static final String ATTACHMENT = "https://hrss.hangzhou.gov.cn/module/download/v1-closed-loop.xlsx";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("career_os")
        .withUsername("career_os")
        .withPassword("career_os");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // 录制回放走的是 ModelPlanner 的同一个解析器和执行器，只是输出事先写好。
        registry.add("career-os.agent.planner", () -> "recorded");
        registry.add("career-os.agent.recorded-turns[0]", () -> "TOOL search_jobs location=杭州\nWHY 先看杭州有没有可信岗位");
        registry.add("career-os.agent.recorded-turns[1]", () -> "TOOL job_facts jobId=" + JOB + "\nWHY 看它的逐条资格和官方原文");
        registry.add("career-os.agent.recorded-turns[2]", () -> "FINISH SINGLE_JOB\nBASIS 2");
    }

    @Test
    void aVerifiedJobReachesTheTrustedPoolThroughAReviewerAndTheAgentRunIsFullyRecorded(
        @Autowired MockMvc mvc,
        @Autowired JdbcTemplate jdbc,
        @Autowired OfficialJobAdmissionService admission
    ) throws Exception {
        insertOfficialJobWithFieldEvidence(jdbc);

        // 1. 自动准入：证据齐全，但"是不是目标范围"要人定。此时可信池是空的。
        var automated = admission.classify(List.of(JOB), Instant.now()).getFirst();
        assertThat(automated.dataQualityStatus().name()).isEqualTo("VERIFIED");
        assertThat(automated.targetScopeStatus().name()).isEqualTo("NEEDS_REVIEW");
        mvc.perform(get("/api/v1/candidates/{id}/job-decisions", CANDIDATE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));

        // 初筛队列里能看到它，页面拿到的内容指纹就是复核时要带上的那一个。
        var matches = mvc.perform(get("/api/v1/candidates/{id}/job-matches", CANDIDATE))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        List<String> fingerprints = JsonPath.read(matches,
            "$.items[?(@.jobId == '" + JOB + "')].jobContentFingerprint");
        assertThat(fingerprints).containsExactly(FINGERPRINT);

        // 2. 复核人纳入。过期的指纹先被拒，确认的必须是他看到的那一版。
        mvc.perform(post("/api/v1/job-admissions/{id}/scope-decisions", JOB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"INCLUDE\",\"contentFingerprint\":\"" + "0".repeat(64) + "\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("JOB_CONTENT_CHANGED"));
        mvc.perform(post("/api/v1/job-admissions/{id}/scope-decisions", JOB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"INCLUDE\",\"contentFingerprint\":\"" + FINGERPRINT
                    + "\",\"note\":\"信息中心岗，职责是信息系统建设\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.admitted").value(true))
            .andExpect(jsonPath("$.reviewer").value(TestSecurityDefaults.OPERATOR));
        assertThat(jdbc.queryForObject(
            "select count(*) from job_admission_scope_decision where job_posting_id = ? and decision = 'INCLUDE'",
            Integer.class, JOB)).isEqualTo(1);

        // 3. 资格与分层对它生效：可信池不再为空。
        mvc.perform(get("/api/v1/candidates/{id}/job-decisions", CANDIDATE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].jobId").value(JOB.toString()))
            .andExpect(jsonPath("$.items[0].tier").isNotEmpty())
            // 推荐要逐条说得出为什么，并指回官方文件。
            .andExpect(jsonPath("$.items[0].eligibilityRules.length()").value(9))
            .andExpect(jsonPath("$.items[0].officialSourceUrl").value(ATTACHMENT));

        // 4. Agent：查、看详情、按模板收尾。
        var body = mvc.perform(post("/api/v1/candidates/{id}/agent-runs", CANDIDATE)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"杭州有哪些可以看的信息中心岗\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("FINISHED"))
            .andExpect(jsonPath("$.plannerIdentity").value("recorded-replay"))
            .andExpect(jsonPath("$.jobs[0].jobPostingId").value(JOB.toString()))
            .andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(body, "$.runId");

        // 5. 台账：每一步、每一步的证据、每一轮模型原文。
        var run = mvc.perform(get("/api/v1/candidates/{id}/agent-runs/{runId}", CANDIDATE, runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.plannerIdentity").value("recorded-replay"))
            .andExpect(jsonPath("$.steps.length()").value(2))
            .andExpect(jsonPath("$.steps[0].tool").value("search_jobs"))
            .andExpect(jsonPath("$.steps[1].tool").value("job_facts"))
            .andExpect(jsonPath("$.modelTurns.length()").value(3))
            .andExpect(jsonPath("$.modelTurns[2].response").value("FINISH SINGLE_JOB\nBASIS 2"))
            .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> searchEvidence = JsonPath.read(run, "$.steps[0].evidence");
        // 岗位来自官方岗位表附件：决策上下文取的是最新的官方附件，台账记的就是它。
        assertThat(searchEvidence).anySatisfy(ref -> {
            assertThat(ref.get("kind")).isEqualTo("OFFICIAL_SOURCE");
            assertThat(ref.get("sourceUrl")).isEqualTo(ATTACHMENT);
        });
        List<Map<String, Object>> factEvidence = JsonPath.read(run, "$.steps[1].evidence[?(@.kind == 'FIELD')]");
        assertThat(factEvidence).anySatisfy(ref -> {
            assertThat(ref.get("field")).isEqualTo("majorRequirementText");
            assertThat(ref.get("sourceUrl")).isEqualTo(ATTACHMENT);
            assertThat((String) ref.get("excerpt")).contains("计算机科学与技术");
        });

        mvc.perform(get("/api/v1/candidates/{id}/agent-runs", CANDIDATE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].runId").value(runId))
            .andExpect(jsonPath("$[0].stepCount").value(2));
    }

    /**
     * 与导入服务写入的形状一致：公告（带截止日与用工身份字段证据）、岗位表附件证据、
     * 岗位行与每个关键字段的原文片段。
     */
    private static void insertOfficialJobWithFieldEvidence(JdbcTemplate jdbc) {
        UUID organization = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID attachment = UUID.randomUUID();
        jdbc.update("""
            insert into organization (id, name, organization_type, administrative_level, province, city)
            values (?, '杭州市数据资源服务中心', 'PUBLIC_INSTITUTION', '市级', '浙江', '杭州')
            """, organization);
        jdbc.update("""
            insert into recruitment_event
              (id, title, recruitment_year, event_type, published_on, application_ends_on, source_url,
               default_employment_type)
            values (?, '2026年杭州市属事业单位公开招聘公告', 2026, 'PUBLIC_INSTITUTION', ?, ?, ?, 'PUBLIC_INSTITUTION_FORMAL')
            """, event, LocalDate.of(2026, 9, 1), LocalDate.now().plusDays(30), ANNOUNCEMENT);
        jdbc.update("""
            insert into evidence (id, evidence_type, source_url, source_title, content_hash, captured_at)
            values (?, 'OFFICIAL_ATTACHMENT', ?, '2026年公开招聘岗位表', ?, now())
            """, attachment, ATTACHMENT, "e".repeat(64));
        jdbc.update("""
            insert into job_posting
              (id, recruitment_event_id, organization_id, external_job_code, title, job_family, employment_type,
               location, headcount, minimum_education, exact_majors, maximum_age, age_reference_date, duties,
               source_url, evidence_ids, stable_job_key, content_fingerprint)
            values (?, ?, ?, 'HZ-2026-017', '信息中心工作人员', 'INFORMATION_SYSTEMS', 'PUBLIC_INSTITUTION_FORMAL',
                    '杭州', 1, 'BACHELOR', '["计算机科学与技术"]'::jsonb, 35, ?, '负责信息系统建设与运维',
                    ?, ?::jsonb, 'v1-closed-loop:HZ-2026-017', ?)
            """, JOB, event, organization, LocalDate.of(2026, 9, 1), ATTACHMENT, "[\"" + attachment + "\"]",
            FINGERPRINT);
        var fields = Map.of(
            "title", "信息中心工作人员",
            "organizationName", "杭州市数据资源服务中心",
            "headcount", "1",
            "educationRequirementText", "本科及以上",
            "majorRequirementText", "计算机科学与技术",
            "ageRequirementText", "35周岁及以下",
            "applicationEndsAt", "报名截止时间：2026年10月24日17:00",
            "employmentType", "录用后签订事业单位聘用合同");
        int row = 12;
        for (var field : fields.entrySet()) {
            UUID fragment = UUID.randomUUID();
            jdbc.update("""
                insert into evidence_fragment (id, evidence_id, locator_type, locator, verbatim_text, content_hash)
                values (?, ?, 'SPREADSHEET', ?::jsonb, ?, ?)
                """, fragment, attachment, "{\"sheet\":\"岗位表\",\"row\":" + row++ + "}", field.getValue(),
                "d".repeat(64));
            jdbc.update("""
                insert into job_field_evidence
                  (id, job_posting_id, field_name, fact_status, evidence_fragment_id, raw_value, extractor_version)
                values (?, ?, ?, 'EXPLICIT', ?, ?, 'v1-closed-loop-fixture')
                """, UUID.randomUUID(), JOB, field.getKey(), fragment, field.getValue());
        }
    }
}
