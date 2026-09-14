package com.careeros;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.careeros.application.personal.JobWatchlistService;
import com.careeros.application.personal.JobWatchlistService.ReadState;
import com.careeros.application.personal.JobWatchlistService.WatchedJobView;
import com.careeros.domain.DomainEnums.EligibilityStatus;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class JobWatchlistApiTest {
    @Test void listPreservesReadReasonsAndAssessmentAccountingInTheHttpResponse() throws Exception {
        UUID candidate = UUID.randomUUID();
        LocalDate asOf = LocalDate.of(2026, 9, 14);
        var rows = new ArrayList<WatchedJobView>();
        for (int index = 0; index < 49; index++) rows.add(row(ReadState.UNCHANGED));
        rows.add(row(ReadState.UNAVAILABLE));
        rows.add(row(ReadState.NOT_REFRESHED));
        var service = mock(JobWatchlistService.class);
        when(service.list(candidate, asOf))
            .thenReturn(new JobWatchlistService.Watchlist(candidate, asOf, rows, 50, 50));
        var mvc = MockMvcBuilders.standaloneSetup(new JobWatchlistController(service)).build();

        mvc.perform(get("/api/v1/candidates/{candidateId}/watched-jobs", candidate)
                .param("asOf", asOf.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assessmentCallsSpent").value(50))
            .andExpect(jsonPath("$.assessmentCallLimit").value(50))
            .andExpect(jsonPath("$.items.length()").value(51))
            .andExpect(jsonPath("$.items[0].readState").value("UNCHANGED"))
            .andExpect(jsonPath("$.items[0].errorCode").doesNotExist())
            .andExpect(jsonPath("$.items[0].evaluationCounted").value(true))
            .andExpect(jsonPath("$.items[49].readState").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.items[49].errorCode").value("EVALUATION_FAILED"))
            .andExpect(jsonPath("$.items[49].evaluationCounted").value(true))
            .andExpect(jsonPath("$.items[49].currentStatus").doesNotExist())
            .andExpect(jsonPath("$.items[50].readState").value("NOT_REFRESHED"))
            .andExpect(jsonPath("$.items[50].errorCode").value("CALL_BUDGET_EXHAUSTED"))
            .andExpect(jsonPath("$.items[50].evaluationCounted").value(false))
            .andExpect(jsonPath("$.items[50].currentStatus").doesNotExist());
        verify(service).list(candidate, asOf);
        verifyNoMoreInteractions(service);
    }

    private static WatchedJobView row(ReadState state) {
        UUID jobId = UUID.randomUUID();
        boolean readable = state == ReadState.UNCHANGED;
        return new WatchedJobView(jobId, readable ? "信息技术岗位" : null,
            readable ? "信息中心" : null, EligibilityStatus.NEEDS_CONFIRMATION,
            readable ? EligibilityStatus.NEEDS_CONFIRMATION : null,
            false, false, null, false, readable ? "v7" : null, "/opportunities/" + jobId,
            state, state == ReadState.UNAVAILABLE ? "EVALUATION_FAILED"
                : state == ReadState.NOT_REFRESHED ? "CALL_BUDGET_EXHAUSTED" : null,
            state != ReadState.NOT_REFRESHED);
    }
}
