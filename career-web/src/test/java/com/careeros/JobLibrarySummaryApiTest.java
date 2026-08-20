package com.careeros;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.careeros.application.JobLibrarySummaryService;
import com.careeros.application.JobLibrarySummaryService.JobLibrarySummary;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class JobLibrarySummaryApiTest {

    @Test
    void exposesTruthfulJobLibraryInventory() throws Exception {
        var service = mock(JobLibrarySummaryService.class);
        when(service.load()).thenReturn(new JobLibrarySummary(
            2291, 2291, 0, 0, 0, 0, 0, 0,
            0, 0, 2291, 0));
        var mvc = MockMvcBuilders.standaloneSetup(new JobLibrarySummaryController(service)).build();

        mvc.perform(get("/api/v1/job-library/summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2291))
            .andExpect(jsonPath("$.raw").value(2291))
            .andExpect(jsonPath("$.parsed").value(0))
            .andExpect(jsonPath("$.failed").value(0))
            .andExpect(jsonPath("$.needsReview").value(2291))
            .andExpect(jsonPath("$.opportunityReady").value(0));
    }
}
