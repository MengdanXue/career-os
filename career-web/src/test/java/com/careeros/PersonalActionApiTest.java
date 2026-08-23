package com.careeros;

import static com.careeros.application.personal.CandidateEvidenceTask.EvidenceStrength.NONE;
import static com.careeros.application.personal.CandidateEvidenceTask.EvidenceTaskKind.EMPLOYMENT;
import static com.careeros.application.personal.PersonalAction.ActionKind.CANDIDATE_EVIDENCE;
import static com.careeros.domain.CandidateFacts.CandidateFactKey.EMPLOYMENT_HISTORY;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.careeros.application.personal.CandidateEvidenceTask;
import com.careeros.application.personal.CandidateEvidenceTaskService;
import com.careeros.application.personal.CandidateEvidenceTaskService.CandidateEvidenceTasks;
import com.careeros.application.personal.PersonalAction;
import com.careeros.application.personal.PersonalActionService;
import com.careeros.application.personal.PersonalActionService.PersonalActions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PersonalActionApiTest {
    private static final UUID CANDIDATE_ID = UUID.fromString("01992f09-0000-7000-8000-000000000001");
    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 23);

    @Test
    void returnsPersonalActionsAndEvidenceTasksForTheResolvedDate() throws Exception {
        var actions = mock(PersonalActionService.class);
        var evidence = mock(CandidateEvidenceTaskService.class);
        when(actions.actions(CANDIDATE_ID, AS_OF)).thenReturn(new PersonalActions(
            CANDIDATE_ID, AS_OF, true, null, List.of(new PersonalAction(
                "evidence:VERIFY_EMPLOYMENT_HISTORY", CANDIDATE_EVIDENCE, 2,
                "补齐可核验工作经历", "旧总年数不能作为硬资格证据。", 12,
                null, NONE, "/profile#employment-history"))));
        when(evidence.tasks(CANDIDATE_ID, AS_OF)).thenReturn(new CandidateEvidenceTasks(
            CANDIDATE_ID, AS_OF, true, null, List.of(new CandidateEvidenceTask(
                "VERIFY_EMPLOYMENT_HISTORY", EMPLOYMENT, EMPLOYMENT_HISTORY,
                "补齐可核验工作经历", "旧总年数不能作为硬资格证据。", 12,
                NONE, "/profile#employment-history"))));
        var controller = new PersonalActionController(actions, evidence,
            Clock.fixed(Instant.parse("2026-08-23T10:00:00Z"), ZoneOffset.UTC));
        var mvc = mvc(controller);

        mvc.perform(get("/api/v1/candidates/{candidateId}/personal-actions", CANDIDATE_ID)
                .queryParam("asOf", "2026-08-23"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.candidateId").value(CANDIDATE_ID.toString()))
            .andExpect(jsonPath("$.asOf").value("2026-08-23"))
            .andExpect(jsonPath("$.items[0].kind").value("CANDIDATE_EVIDENCE"))
            .andExpect(jsonPath("$.items[0].affectedObjectCount").value(12))
            .andExpect(jsonPath("$.items[0].deepLink").value("/profile#employment-history"));

        mvc.perform(get("/api/v1/candidates/{candidateId}/evidence-tasks", CANDIDATE_ID)
                .queryParam("asOf", "2026-08-23"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].factKey").value("EMPLOYMENT_HISTORY"))
            .andExpect(jsonPath("$.items[0].affectedJobCount").value(12));
    }

    @Test
    void defaultsAsOfToTheServerClockDate() throws Exception {
        var actions = mock(PersonalActionService.class);
        var evidence = mock(CandidateEvidenceTaskService.class);
        when(actions.actions(CANDIDATE_ID, AS_OF)).thenReturn(
            new PersonalActions(CANDIDATE_ID, AS_OF, true, null, List.of()));
        var controller = new PersonalActionController(actions, evidence,
            Clock.fixed(Instant.parse("2026-08-23T10:00:00Z"), ZoneOffset.UTC));
        var mvc = mvc(controller);

        mvc.perform(get("/api/v1/candidates/{candidateId}/personal-actions", CANDIDATE_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.asOf").value("2026-08-23"));
    }

    private static MockMvc mvc(PersonalActionController controller) {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();
    }
}
