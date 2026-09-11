package com.careeros;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.careeros.application.ExtractionExceptions;
import com.careeros.application.ExtractionPorts.ReviewPage;
import com.careeros.application.ReviewService;
import com.careeros.domain.DomainEnums.ReviewDecision;
import com.careeros.domain.DomainEnums.ReviewStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ReviewController.class)
@Import(SecurityConfiguration.class)
@WithMockUser(username = "reviewer-a", roles = "REVIEWER")
class ReviewApiTest {
    @Autowired MockMvc mvc;
    @MockBean ReviewService service;

    @Test
    void pendingReviewsArePagedAndDetailCanBeRead() throws Exception {
        when(service.findPage(ReviewStatus.PENDING, 0, 20)).thenReturn(
            new ReviewPage(List.of(ApiTestFixtures.review()), 0, 20, 1));
        when(service.find(ApiTestFixtures.REVIEW_ID)).thenReturn(ApiTestFixtures.details());

        mvc.perform(get("/api/v1/reviews?status=PENDING&page=0&size=20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].id").value(ApiTestFixtures.REVIEW_ID.toString()))
            .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/reviews/{id}", ApiTestFixtures.REVIEW_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.item.id").value(ApiTestFixtures.REVIEW_ID.toString()));
    }

    @Test
    void allFourReviewActionsUseTheSameEndpoint() throws Exception {
        when(service.act(any())).thenReturn(ApiTestFixtures.details());
        for (ReviewDecision decision : ReviewDecision.values()) {
            String corrected = decision == ReviewDecision.CORRECT
                ? ",\"correctedPayload\":" + proposalJson()
                : "";
            mvc.perform(post("/api/v1/reviews/{id}/actions", ApiTestFixtures.REVIEW_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"decision\":\"" + decision + "\",\"expectedVersion\":0" + corrected + "}"))
                .andExpect(status().isOk());
        }
    }

    @Test
    void staleVersionReturnsConflictAndCorrectionRequiresPayload() throws Exception {
        when(service.act(any())).thenThrow(new ExtractionExceptions.ReviewConflictException("stale"));
        mvc.perform(post("/api/v1/reviews/{id}/actions", ApiTestFixtures.REVIEW_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"CONFIRM\",\"expectedVersion\":3}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("REVIEW_CONFLICT"));

        mvc.perform(post("/api/v1/reviews/{id}/actions", ApiTestFixtures.REVIEW_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"CORRECT\",\"expectedVersion\":0}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private static String proposalJson() throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
            .writeValueAsString(ApiTestFixtures.proposal());
    }
}
