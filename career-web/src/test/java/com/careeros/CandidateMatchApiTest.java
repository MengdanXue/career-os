package com.careeros;

import static com.careeros.domain.DomainEnums.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.careeros.application.CandidateMatchService;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CandidateMatchApiTest {
    @Test
    void listsCandidateMatchesWithIdentityWarningAndOfficialSource() throws Exception {
        UUID candidateId = UUID.randomUUID(), jobId = UUID.randomUUID();
        CandidateMatchService service = mock(CandidateMatchService.class);
        when(service.list(eq(candidateId), any(), any())).thenReturn(new CandidateMatchService.MatchPage(
            List.of(new CandidateMatchService.CandidateMatch(jobId, "101", "信息中心工作人员", "杭州市西溪医院",
                "杭州", EligibilityStatus.ELIGIBLE, 65, 60, EmploymentType.UNKNOWN, false,
                Set.of(JobAdmissionReason.EMPLOYMENT_IDENTITY_UNKNOWN), List.of("用工身份待官方证据确认"),
                "https://hrss.hangzhou.gov.cn/art/2026/notice.html", "a".repeat(64),
                1, JobFamily.INFORMATION_SYSTEMS, EducationLevel.MASTER,
                Set.of("计算机科学与技术"), Set.of(2026), 38, LocalDate.of(2026, 8, 1), null,
                Set.of("中级"), "医院信息系统建设和数据库管理", "2026年公开招聘",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 20))), 0, 20, 1));
        var objectMapper = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        var mvc = MockMvcBuilders.standaloneSetup(new CandidateMatchController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .setControllerAdvice(new ApiExceptionHandler()).build();

        mvc.perform(get("/api/v1/candidates/{candidateId}/job-matches", candidateId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].jobTitle").value("信息中心工作人员"))
            .andExpect(jsonPath("$.items[0].externalJobCode").value("101"))
            .andExpect(jsonPath("$.items[0].employmentIdentityConfirmed").value(false))
            .andExpect(jsonPath("$.items[0]").value(org.hamcrest.Matchers.hasKey("actualEmployer")))
            .andExpect(jsonPath("$.items[0]").value(org.hamcrest.Matchers.hasKey("worksite")))
            .andExpect(jsonPath("$.items[0]").value(org.hamcrest.Matchers.hasKey("employmentEvidence")))
            .andExpect(jsonPath("$.items[0].exactMajors[0]").value("计算机科学与技术"))
            .andExpect(jsonPath("$.items[0].duties").value("医院信息系统建设和数据库管理"))
            .andExpect(jsonPath("$.items[0].applicationEndsOn").value("2026-07-20"))
            .andExpect(jsonPath("$.items[0].sourceUrl").value("https://hrss.hangzhou.gov.cn/art/2026/notice.html"));
    }
}
