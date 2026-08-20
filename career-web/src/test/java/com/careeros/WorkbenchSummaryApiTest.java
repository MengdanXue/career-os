package com.careeros;

import static com.careeros.application.workbench.WorkbenchPorts.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.careeros.application.workbench.WorkbenchSummaryService;
import com.careeros.domain.DomainEnums.EligibilityStatus;
import com.careeros.domain.DomainEnums.OpportunityTier;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkbenchSummaryApiTest {
    @Test
    void returnsTheDailyWorkbenchReadModelForOneCandidate() throws Exception {
        UUID candidateId = UUID.fromString("01992f09-0000-7000-8000-000000000001");
        Instant now = Instant.parse("2026-08-20T10:00:00Z");
        DecisionOverview decisions = (id, at) -> List.of(new DecisionSignal(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), "信息中心 Java 岗", "杭州市数字事业中心", "杭州",
            EligibilityStatus.ELIGIBLE, OpportunityTier.T1, 82, 88, 60, LocalDate.of(2026, 8, 28)));
        var service = new WorkbenchSummaryService(decisions, at -> new AcquisitionSnapshot(List.of(), List.of()), () -> 3,
            Clock.fixed(now, ZoneOffset.UTC));
        var mvc = MockMvcBuilders.standaloneSetup(new WorkbenchSummaryController(service)).build();

        mvc.perform(get("/api/v1/candidates/{candidateId}/workbench-summary", candidateId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.candidateId").value(candidateId.toString()))
            .andExpect(jsonPath("$.tierCounts.t1").value(1))
            .andExpect(jsonPath("$.deadlines[0].jobTitle").value("信息中心 Java 岗"))
            .andExpect(jsonPath("$.deadlines[0].daysRemaining").value(8))
            .andExpect(jsonPath("$.reviews.pending").value(3));
    }
}
