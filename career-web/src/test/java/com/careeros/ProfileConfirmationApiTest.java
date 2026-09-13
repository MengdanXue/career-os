package com.careeros;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.careeros.application.personal.CandidateEvidenceTask.EvidenceStrength;
import com.careeros.application.AgentSessionService;
import com.careeros.application.personal.ProfileConfirmationService;
import com.careeros.application.personal.ProfileConfirmationService.ConfirmationOutcome;
import com.careeros.application.personal.ProfileConfirmationService.DeclaredChange;
import com.careeros.application.personal.ProfileConfirmationService.DeclaredValue;
import com.careeros.application.personal.ProfileConfirmationService.Result;
import com.careeros.domain.CandidateFacts.CandidateFactKey;
import com.careeros.domain.DomainEnums.PoliticalAffiliation;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProfileConfirmationApiTest {
    private static final UUID CANDIDATE_ID = UUID.randomUUID();

    private static final UUID SESSION_ID = UUID.randomUUID();

    private ProfileConfirmationService service;
    private AgentSessionService sessions;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        service = mock(ProfileConfirmationService.class);
        sessions = mock(AgentSessionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ProfileConfirmationController(service, sessions))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static String body(String factKey, String value, boolean acknowledged) {
        return """
            {"factKey":"%s","value":"%s","expectedProfileVersion":"profile-7",
             "idempotencyKey":"key-1","acknowledgedChange":%s}
            """.formatted(factKey, value, acknowledged);
    }

    private static String sessionBody(String factKey, String value, UUID sessionId, String claimedVersion) {
        return """
            {"factKey":"%s","value":"%s","sessionId":"%s","expectedProfileVersion":"%s",
             "idempotencyKey":"key-1","acknowledgedChange":false}
            """.formatted(factKey, value, sessionId, claimedVersion);
    }

    /** 回答按本人声明记录，返回里要看得见证据等级与那句"不是官方核实"。 */
    @Test void recordsAnAnswerAsSelfReported() throws Exception {
        when(service.record(any(), any())).thenReturn(new ConfirmationOutcome(Result.RECORDED,
            EvidenceStrength.SELF_REPORTED, "profile-7", "profile-8",
            "已按你本人的声明记录。这是自述信息，不是官方核实结果。", null, null));

        mvc.perform(post("/api/v1/candidates/{candidateId}/profile-confirmations", CANDIDATE_ID)
                .param("asOf", "2026-08-24")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("POLITICAL_AFFILIATION", "CPC_MEMBER", false)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result").value("RECORDED"))
            .andExpect(jsonPath("$.evidenceStrength").value("SELF_REPORTED"))
            .andExpect(jsonPath("$.profileVersionAfter").value("profile-8"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不是官方核实")));

        verify(service).record(argThat(request ->
            request.candidateId().equals(CANDIDATE_ID)
                && request.factKey() == CandidateFactKey.POLITICAL_AFFILIATION
                && request.value() instanceof DeclaredValue.OfPoliticalAffiliation value
                && value.value() == PoliticalAffiliation.CPC_MEMBER
                && request.expectedProfileVersion().equals("profile-7")
                && request.idempotencyKey().equals("key-1")
                && !request.acknowledgedChange()), any());
    }

    /** 待确认的变更要说清改什么，但不承诺会解锁多少岗位。 */
    @Test void surfacesAPendingChangeWithoutPromisingUnlockedJobs() throws Exception {
        when(service.record(any(), any())).thenReturn(new ConfirmationOutcome(
            Result.CHANGE_REQUIRES_ACKNOWLEDGEMENT, EvidenceStrength.SELF_REPORTED, "profile-7", "profile-7",
            "这会把「政治面貌」从「NON_MEMBER」改成「CPC_MEMBER」。改完要重算才知道各岗位结论有没有变。",
            new DeclaredChange(CandidateFactKey.POLITICAL_AFFILIATION, "NON_MEMBER", "CPC_MEMBER"), null));

        mvc.perform(post("/api/v1/candidates/{candidateId}/profile-confirmations", CANDIDATE_ID)
                .param("asOf", "2026-08-24")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("POLITICAL_AFFILIATION", "CPC_MEMBER", false)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result").value("CHANGE_REQUIRES_ACKNOWLEDGEMENT"))
            .andExpect(jsonPath("$.pendingChange.from").value("NON_MEMBER"))
            .andExpect(jsonPath("$.pendingChange.to").value("CPC_MEMBER"))
            .andExpect(jsonPath("$.changes").doesNotExist());
    }

    /**
     * 听不懂的取值就是 400，不猜。把一句听不懂的回答勉强塞进某个字段，比直接说没听懂危险得多——
     * 它会安静地成为后续每一个硬资格结论的依据。
     */
    @Test void anUnrecognisedValueIsRejectedRatherThanGuessed() throws Exception {
        mvc.perform(post("/api/v1/candidates/{candidateId}/profile-confirmations", CANDIDATE_ID)
                .param("asOf", "2026-08-24")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("POLITICAL_AFFILIATION", "大概算是吧", false)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(service, never()).record(any(), any());
    }

    /** 要材料的字段在进入服务之前就被拒绝。 */
    @Test void aFactThatNeedsDocumentsNeverReachesTheService() throws Exception {
        mvc.perform(post("/api/v1/candidates/{candidateId}/profile-confirmations", CANDIDATE_ID)
                .param("asOf", "2026-08-24")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("EDUCATION_RECORDS", "MASTER", false)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(service, never()).record(any(), any());
    }

    /**
     * 带了会话，基准版本取自会话，客户端自报的版本不作数。
     *
     * <p>否则那个检查等于没有：客户端只要报上当前版本就永远通过，而它的意义正是
     * "用户看到的那一版和现在的不一样"。
     */
    @Test void aClaimedProfileVersionCannotOverrideTheOneTheSessionRecorded() throws Exception {
        when(sessions.profileVersionSeenBy(SESSION_ID)).thenReturn(java.util.Optional.of("profile-the-user-saw"));
        when(service.record(any(), any())).thenReturn(new ConfirmationOutcome(Result.RECORDED,
            EvidenceStrength.SELF_REPORTED, "profile-the-user-saw", "profile-8", "已记录。", null, null));

        mvc.perform(post("/api/v1/candidates/{candidateId}/profile-confirmations", CANDIDATE_ID)
                .param("asOf", "2026-08-24")
                .contentType(MediaType.APPLICATION_JSON)
                .content(sessionBody("POLITICAL_AFFILIATION", "CPC_MEMBER", SESSION_ID, "profile-current")))
            .andExpect(status().isOk());

        verify(service).record(argThat(request ->
            request.expectedProfileVersion().equals("profile-the-user-saw")), any());
    }

    /** 会话不存在就不能确认：没有基准版本，那个检查无从谈起。 */
    @Test void aMissingSessionIsRefusedRatherThanFallingBackToTheClaimedVersion() throws Exception {
        when(sessions.profileVersionSeenBy(SESSION_ID)).thenReturn(java.util.Optional.empty());

        mvc.perform(post("/api/v1/candidates/{candidateId}/profile-confirmations", CANDIDATE_ID)
                .param("asOf", "2026-08-24")
                .contentType(MediaType.APPLICATION_JSON)
                .content(sessionBody("POLITICAL_AFFILIATION", "CPC_MEMBER", SESSION_ID, "profile-current")))
            .andExpect(status().isBadRequest());

        verify(service, never()).record(any(), any());
    }

    /** 覆盖既有答案的确认标记要原样传到服务，不能在这一层被默默打开。 */
    @Test void theAcknowledgementFlagIsPassedThroughUnchanged() throws Exception {
        when(service.record(any(), any())).thenReturn(new ConfirmationOutcome(Result.RECORDED,
            EvidenceStrength.SELF_REPORTED, "profile-7", "profile-8", "已记录。", null, null));

        mvc.perform(post("/api/v1/candidates/{candidateId}/profile-confirmations", CANDIDATE_ID)
                .param("asOf", "2026-08-24")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("POLITICAL_AFFILIATION", "CPC_MEMBER", true)))
            .andExpect(status().isOk());

        verify(service).record(argThat(ProfileConfirmationService.ConfirmationRequest::acknowledgedChange), any());
    }
}
