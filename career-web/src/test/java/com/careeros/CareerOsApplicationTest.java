package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.careeros.application.ExtractionPorts.DocumentParser;
import com.careeros.application.ExtractionPorts.ExtractionBundle;
import com.careeros.application.ExtractionPorts.ExtractionPersistence;
import com.careeros.infrastructure.persistence.JobPostingJpaRepository;
import com.careeros.infrastructure.persistence.CandidateProfileJpaRepository;
import com.careeros.infrastructure.persistence.OfficialExcelImportService;
import com.careeros.infrastructure.extraction.ExtractionRunJpaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.io.*;
import java.time.LocalDate;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Assumptions;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "career-os.acquisition.scheduling-enabled=false")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CareerOsApplicationTest {
    @MockitoSpyBean
    ExtractionPersistence extractionPersistence;

    @MockitoSpyBean
    DocumentParser documentParser;

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
        registry.add("spring.datasource.hikari.connection-timeout", () -> 1000);
    }

    @Test
    void contextStartsWithMigratedSchemaAndRepositories(
        @Autowired JobPostingJpaRepository jobs,
        @Autowired CandidateProfileJpaRepository candidates
    ) {
        assertThat(jobs).isNotNull();
        assertThat(candidates.count()).isEqualTo(1);
    }

    @Test
    void extractionApiPersistsAReviewAndReusesIdenticalContent(
        @Autowired MockMvc mvc,
        @Autowired ObjectMapper json
    ) throws Exception {
        byte[] html = "<html><body><h1>2026年杭州端到端采集测试公告</h1></body></html>"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] metadata = """
            {"sourceUrl":"https://example.test/extraction-e2e","sourceTitle":"端到端采集测试公告",
             "capturedAt":"2026-08-14T15:00:00Z","requireModel":false}
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        var first = mvc.perform(multipart("/api/v1/extractions")
                .file(new MockMultipartFile("document", "notice.html", "text/html", html))
                .file(new MockMultipartFile("metadata", "metadata.json", "application/json", metadata)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.reused").value(false))
            .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"))
            .andReturn().getResponse().getContentAsString();
        String reviewId = json.readTree(first).path("reviewId").asText();

        mvc.perform(multipart("/api/v1/extractions")
                .file(new MockMultipartFile("document", "notice.html", "text/html", html))
                .file(new MockMultipartFile("metadata", "metadata.json", "application/json", metadata)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reused").value(true));
        mvc.perform(get("/api/v1/reviews/{id}", reviewId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.item.status").value("PENDING"));
    }

    @Test
    void openApiDocumentsExtractionReviewAndDecisionResources(@Autowired MockMvc mvc) throws Exception {
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/extractions']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/reviews']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/candidates/{candidateId}/job-decisions']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/candidates/{candidateId}/job-decisions/{jobId}']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/candidates/{candidateId}/agent-queries']").exists());
    }

    @Test
    void decisionApiPersistsAndReusesVersionedSnapshot(
        @Autowired MockMvc mvc,@Autowired ObjectMapper json,@Autowired JdbcTemplate jdbc
    ) throws Exception {
        String suffix=UUID.randomUUID().toString();
        String organization=json.readTree(mvc.perform(post("/api/v1/organizations").contentType(MediaType.APPLICATION_JSON).content("""
            {"name":"杭州决策测试单位-%s","organizationType":"PUBLIC_INSTITUTION","province":"浙江","city":"杭州"}
            """.formatted(suffix))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText();
        String event=json.readTree(mvc.perform(post("/api/v1/recruitment-events").contentType(MediaType.APPLICATION_JSON).content("""
            {"title":"决策测试公告","recruitmentYear":2026,"eventType":"PUBLIC_INSTITUTION","applicationEndsOn":"2026-09-30","sourceUrl":"https://example.test/decision/%s","defaultEmploymentType":"ESTABLISHMENT"}
            """.formatted(suffix))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText();
        String job=json.readTree(mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content("""
            {"recruitmentEventId":"%s","organizationId":"%s","externalJobCode":"D01","title":"信息中心Java岗","jobFamily":"INFORMATION_SYSTEMS","employmentType":"ESTABLISHMENT","location":"杭州","headcount":1,"minimumEducation":"MASTER","exactMajors":["计算机科学与技术"],"acceptedGraduationYears":[],"requiredProfessionalTitles":[],"duties":"Java PostgreSQL 数据治理","sourceUrl":"https://example.test/decision/%s"}
            """.formatted(event,organization,suffix))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText();

        String first=mvc.perform(post("/api/v1/candidates/{candidateId}/job-decisions/{jobId}","01992f09-0000-7000-8000-000000000001",job))
            .andExpect(status().isOk()).andExpect(jsonPath("$.eligibilityStatus").value("ELIGIBLE"))
            .andExpect(jsonPath("$.tier").value("T3")).andExpect(jsonPath("$.stability.coveragePercent").value(40))
            .andReturn().getResponse().getContentAsString();
        String second=mvc.perform(post("/api/v1/candidates/{candidateId}/job-decisions/{jobId}","01992f09-0000-7000-8000-000000000001",job))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(second).path("decisionId").asText()).isEqualTo(json.readTree(first).path("decisionId").asText());
        assertThat(jdbc.queryForObject("select count(*) from decision_assessment where job_posting_id=?",Long.class,UUID.fromString(job))).isEqualTo(1L);
    }

    @Test
    void candidateDecisionProfileCanBeUpdatedThroughItsResource(@Autowired MockMvc mvc) throws Exception {
        mvc.perform(put("/api/v1/candidates/{id}", "01992f09-0000-7000-8000-000000000001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "displayName":"候选人",
                      "birthYear":1992,
                      "birthMonth":9,
                      "highestEducation":"MASTER",
                      "majors":["计算机科学与技术"],
                      "graduationYear":2018,
                      "experienceYears":6,
                      "professionalTitles":[],
                      "preferredLocations":["杭州"],
                      "acceptedEmploymentTypes":["ESTABLISHMENT","CONTRACT"],
                      "profileVersion":"profile-api-v2",
                      "skills":["Java","PostgreSQL"],
                      "researchKeywords":["数据治理"],
                      "targetJobFamilies":["INFORMATION_SYSTEMS"],
                      "preferredOrganizationTypes":["PUBLIC_INSTITUTION"]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profileVersion").value("profile-api-v2"))
            .andExpect(jsonPath("$.skills", hasItem("Java")));
    }

    @Test
    void applicationTaskExecutorUsesJava21VirtualThreads(@Autowired ApplicationContext context) throws Exception {
        AsyncTaskExecutor executor = context.getBean("applicationTaskExecutor", AsyncTaskExecutor.class);
        assertThat(executor.submit(() -> Thread.currentThread().isVirtual()).get()).isTrue();
    }

    @Test
    void requiredModelFailureIsPersistedForDiagnostics(
        @Autowired MockMvc mvc,
        @Autowired ExtractionRunJpaRepository runs,
        @Autowired HikariDataSource dataSource
    ) throws Exception {
        long failuresBefore = runs.countByStatus(
            com.careeros.domain.DomainEnums.DataQualityStatus.FAILED);
        byte[] metadata = """
            {"sourceUrl":"https://example.test/model-required-failure",
             "sourceTitle":"模型失败留痕测试","capturedAt":"2026-08-14T15:00:00Z",
             "requireModel":true}
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        withSingleConnection(dataSource, () ->
            mvc.perform(multipart("/api/v1/extractions")
                    .file(new MockMultipartFile(
                        "document", "required.html", "text/html",
                        "<html><body>unique required model failure</body></html>"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .file(new MockMultipartFile(
                        "metadata", "metadata.json", "application/json", metadata)))
                .andExpect(status().isServiceUnavailable()));

        assertThat(runs.countByStatus(com.careeros.domain.DomainEnums.DataQualityStatus.FAILED))
            .isEqualTo(failuresBefore + 1);
    }

    @Test
    void failureAfterBundleFlushRollsBackBeforePersistingDiagnostic(
        @Autowired MockMvc mvc,
        @Autowired ExtractionRunJpaRepository runs,
        @Autowired HikariDataSource dataSource
    ) throws Exception {
        long failuresBefore = runs.countByStatus(
            com.careeros.domain.DomainEnums.DataQualityStatus.FAILED);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("forced failure after extraction bundle flush");
        }).when(extractionPersistence).save(any(ExtractionBundle.class));
        byte[] metadata = """
            {"sourceUrl":"https://example.test/post-flush-failure",
             "sourceTitle":"事务回滚测试","capturedAt":"2026-08-14T15:00:00Z",
             "requireModel":false}
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        try {
            withSingleConnection(dataSource, () ->
                org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    mvc.perform(multipart("/api/v1/extractions")
                            .file(new MockMultipartFile(
                                "document", "post-flush.html", "text/html",
                                "<html><body>unique post flush failure</body></html>"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                            .file(new MockMultipartFile(
                                "metadata", "metadata.json", "application/json", metadata))))
                    .hasRootCauseMessage("forced failure after extraction bundle flush"));
        } finally {
            reset(extractionPersistence);
        }

        assertThat(runs.countByStatus(com.careeros.domain.DomainEnums.DataQualityStatus.FAILED))
            .isEqualTo(failuresBefore + 1);
    }

    @Test
    void concurrentIdenticalFailuresUseOneParseAndOneFailedRunWithSingleConnection(
        @Autowired MockMvc mvc,
        @Autowired ExtractionRunJpaRepository runs,
        @Autowired HikariDataSource dataSource
    ) throws Exception {
        long failuresBefore = runs.countByStatus(
            com.careeros.domain.DomainEnums.DataQualityStatus.FAILED);
        clearInvocations(documentParser);
        byte[] metadata = """
            {"sourceUrl":"https://example.test/concurrent-model-failure",
             "sourceTitle":"并发失败去重测试","capturedAt":"2026-08-14T15:00:00Z",
             "requireModel":true}
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] html = "<html><body>unique concurrent required model failure</body></html>"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        withSingleConnection(dataSource, () -> {
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(() -> {
                    start.await();
                    return submitFailure(mvc, html, metadata);
                });
                var second = executor.submit(() -> {
                    start.await();
                    return submitFailure(mvc, html, metadata);
                });
                start.countDown();
                assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(200, 503);
            }
        });

        verify(documentParser, times(1)).parse(any(), any(), any());
        assertThat(runs.countByStatus(com.careeros.domain.DomainEnums.DataQualityStatus.FAILED))
            .isEqualTo(failuresBefore + 1);
        clearInvocations(documentParser);
    }

    @Test
    void excelImportIsIncrementalAndMarksMissingJobsInactive(
        @Autowired OfficialExcelImportService imports,
        @Autowired JobPostingJpaRepository jobs
    ) throws Exception {
        var command=new OfficialExcelImportService.ImportCommand("2026杭州市测试招聘","https://example.test/2026/notice",2026,LocalDate.of(2026,3,1),LocalDate.of(2026,3,10),"浙江杭州",com.careeros.domain.DomainEnums.EventType.PUBLIC_INSTITUTION);
        var first=imports.importWorkbook(workbook(new String[][]{{"杭州市测试信息中心","A01","软件开发","2","研究生（硕士及以上）","计算机科学与技术","35周岁以下","事业编制"},{"杭州市测试信息中心","A02","数据分析","1","本科及以上","统计学、计算机科学与技术类","2年以上","事业编制"}}),command);
        assertThat(first.inserted()).isEqualTo(2); assertThat(first.errors()).isEmpty();
        var repeat=imports.importWorkbook(workbook(new String[][]{{"杭州市测试信息中心","A01","软件开发","2","研究生（硕士及以上）","计算机科学与技术","35周岁以下","事业编制"},{"杭州市测试信息中心","A02","数据分析","1","本科及以上","统计学、计算机科学与技术类","2年以上","事业编制"}}),command);
        assertThat(repeat.unchanged()).isEqualTo(2);
        var changed=imports.importWorkbook(workbook(new String[][]{{"杭州市测试信息中心","A01","软件开发","3","研究生（硕士及以上）","计算机科学与技术","35周岁以下","事业编制"},{"杭州市测试信息中心","A02","数据分析","1","本科及以上","统计学、计算机科学与技术类","2年以上","事业编制"}}),command);
        assertThat(changed.updated()).isEqualTo(1); assertThat(changed.unchanged()).isEqualTo(1);
        var removed=imports.importWorkbook(workbook(new String[][]{{"杭州市测试信息中心","A01","软件开发","3","研究生（硕士及以上）","计算机科学与技术","35周岁以下","事业编制"}}),command);
        assertThat(removed.deactivated()).isEqualTo(1); assertThat(jobs.countByActiveFalse()).isEqualTo(1);
        long inactiveBefore=jobs.countByActiveFalse();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(()->imports.importWorkbook(invalidWorkbook(),command))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("拒绝导入");
        assertThat(jobs.countByActiveFalse()).isEqualTo(inactiveBefore);
    }

    @Test
    void crudAndDecisionApiCreateAnOpportunity(@Autowired MockMvc mvc,@Autowired ObjectMapper json) throws Exception {
        var organization=json.readTree(mvc.perform(post("/api/v1/organizations").contentType(MediaType.APPLICATION_JSON).content("""
            {"name":"杭州测试数据中心","organizationType":"PUBLIC_INSTITUTION","province":"浙江","city":"杭州"}
            """)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText();
        var event=json.readTree(mvc.perform(post("/api/v1/recruitment-events").contentType(MediaType.APPLICATION_JSON).content("""
            {"title":"2026测试公告","recruitmentYear":2026,"eventType":"PUBLIC_INSTITUTION","publishedOn":"2026-03-01","sourceUrl":"https://example.test/api-notice","defaultEmploymentType":"UNKNOWN"}
            """)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText();
        var job=json.readTree(mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content("""
            {"recruitmentEventId":"%s","organizationId":"%s","externalJobCode":"API01","title":"Java软件开发","jobFamily":"SOFTWARE","employmentType":"ESTABLISHMENT","location":"杭州","headcount":1,"minimumEducation":"MASTER","exactMajors":["计算机科学与技术"],"acceptedGraduationYears":[],"maximumAge":38,"ageReferenceDate":"2026-03-10","minimumExperienceYears":null,"requiredProfessionalTitles":[],"sourceUrl":"https://example.test/api-notice"}
            """.formatted(event,organization))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText();
        mvc.perform(post("/api/v1/eligibility-assessments").contentType(MediaType.APPLICATION_JSON).content("""
            {"candidateId":"01992f09-0000-7000-8000-000000000001","jobId":"%s"}
            """.formatted(job))).andExpect(status().isOk()).andExpect(jsonPath("$.assessment.status").value("ELIGIBLE")).andExpect(jsonPath("$.opportunity.matchScore").value(100));
        mvc.perform(post("/api/v1/eligibility-assessments").contentType(MediaType.APPLICATION_JSON).content("""
            {"candidateId":"01992f09-0000-7000-8000-000000000001","jobId":"%s"}
            """.formatted(job))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/opportunities")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].status").value("NEW"));
    }

    @Test
    void actualHangzhouUnifiedWorkbookCanBeImportedWhenFixtureIsAvailable(@Autowired OfficialExcelImportService imports) throws Exception {
        Path fixture=findWorkspaceFile("output/career-os-samples/raw/03-hz-unified-2025-plan.xls");
        Assumptions.assumeTrue(Files.exists(fixture),"local official workbook fixture is not available");
        try(var input=Files.newInputStream(fixture)){
            var result=imports.importWorkbook(input,new OfficialExcelImportService.ImportCommand("2025杭州市市属事业单位统一公开招聘","https://hrss.hangzhou.gov.cn/art/2025/3/18/art_1229782005_4338158.html",2025,LocalDate.of(2025,3,18),null,"浙江杭州",com.careeros.domain.DomainEnums.EventType.PUBLIC_INSTITUTION));
            assertThat(result.inserted()+result.updated()+result.unchanged()).isGreaterThanOrEqualTo(200);
            assertThat(result.errors()).hasSizeLessThan(10);
        }
    }

    @Test
    void actual2026UniversityWorkbookCanBeImportedWhenFixtureIsAvailable(@Autowired OfficialExcelImportService imports) throws Exception {
        Path fixture=findWorkspaceFile("output/career-os-samples/raw/06-hdu-2026-second-plan.xlsx");
        Assumptions.assumeTrue(Files.exists(fixture),"local 2026 university workbook fixture is not available");
        try(var input=Files.newInputStream(fixture)){
            var result=imports.importWorkbook(input,new OfficialExcelImportService.ImportCommand("杭州电子科技大学2026年第二批公开招聘","https://www.hdu.edu.cn/2026/recruitment/second",2026,null,null,"浙江杭州",com.careeros.domain.DomainEnums.EventType.UNIVERSITY,"杭州电子科技大学"));
            assertThat(result.inserted()+result.updated()+result.unchanged()).isGreaterThanOrEqualTo(1);
            assertThat(result.errors()).hasSizeLessThan(5);
        }
    }

    private InputStream workbook(String[][] rows) throws Exception {
        try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()){
            var sheet=workbook.createSheet("岗位表"); var header=sheet.createRow(0); String[] columns={"招聘单位","岗位代码","岗位名称","招聘人数","学历要求","专业要求","年龄要求","用工性质"};
            for(int i=0;i<columns.length;i++)header.createCell(i).setCellValue(columns[i]);
            for(int r=0;r<rows.length;r++){var row=sheet.createRow(r+1);for(int c=0;c<rows[r].length;c++)row.createCell(c).setCellValue(rows[r][c]);}
            workbook.write(output); return new ByteArrayInputStream(output.toByteArray());
        }
    }
    private InputStream invalidWorkbook() throws Exception {
        try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()){
            var row=workbook.createSheet("说明").createRow(0);row.createCell(0).setCellValue("这不是岗位表");workbook.write(output);return new ByteArrayInputStream(output.toByteArray());
        }
    }
    private Path findWorkspaceFile(String relative){Path current=Path.of("").toAbsolutePath();for(int i=0;i<5&&current!=null;i++,current=current.getParent()){Path candidate=current.resolve(relative);if(Files.exists(candidate))return candidate;}return Path.of(relative);}

    private static void withSingleConnection(HikariDataSource dataSource, ThrowingAction action)
        throws Exception {
        int originalMaximum = dataSource.getMaximumPoolSize();
        dataSource.setMaximumPoolSize(1);
        dataSource.getHikariPoolMXBean().softEvictConnections();
        try {
            action.run();
        } finally {
            dataSource.setMaximumPoolSize(originalMaximum);
        }
    }

    private static int submitFailure(MockMvc mvc, byte[] html, byte[] metadata) throws Exception {
        return mvc.perform(multipart("/api/v1/extractions")
                .file(new MockMultipartFile("document", "failure.html", "text/html", html))
                .file(new MockMultipartFile(
                    "metadata", "metadata.json", "application/json", metadata)))
            .andReturn().getResponse().getStatus();
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
