package com.careeros;

import static com.careeros.domain.DomainEnums.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.careeros.application.CandidateMatchService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CandidateMatchApiTest {
    @Test
    void listsCandidateMatchesWithIdentityWarningAndOfficialSource() throws Exception {
        UUID candidateId = UUID.randomUUID(), jobId = UUID.randomUUID();
        CandidateMatchService service = mock(CandidateMatchService.class);
        when(service.list(eq(candidateId), any(), any())).thenReturn(new CandidateMatchService.MatchPage(
            List.of(new CandidateMatchService.CandidateMatch(jobId, "信息中心工作人员", "杭州市西溪医院",
                "杭州", EligibilityStatus.ELIGIBLE, 65, 60, EmploymentType.UNKNOWN, false,
                Set.of(JobAdmissionReason.EMPLOYMENT_IDENTITY_UNKNOWN), List.of("用工身份待官方证据确认"),
                "https://hrss.hangzhou.gov.cn/art/2026/notice.html", "a".repeat(64))), 0, 20, 1));
        var mvc = MockMvcBuilders.standaloneSetup(new CandidateMatchController(service))
            .setControllerAdvice(new ApiExceptionHandler()).build();

        mvc.perform(get("/api/v1/candidates/{candidateId}/job-matches", candidateId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].jobTitle").value("信息中心工作人员"))
            .andExpect(jsonPath("$.items[0].employmentIdentityConfirmed").value(false))
            .andExpect(jsonPath("$.items[0].sourceUrl").value("https://hrss.hangzhou.gov.cn/art/2026/notice.html"));
    }
}
