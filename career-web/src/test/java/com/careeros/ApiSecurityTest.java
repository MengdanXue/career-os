package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.careeros.application.ExtractionPorts.ApplyReviewActionCommand;
import com.careeros.application.ReviewService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 人工复核是把模型提案提升为已核验官方数据的唯一闸门。
 * 在加鉴权之前，任何能访问到端口的人都可以 POST 一个 CONFIRM 直接写进正式岗位库。
 * 这些用例锁住"没有身份就进不来"和"身份不能由调用方自称"。
 */
@WebMvcTest(controllers = ReviewController.class)
@Import(SecurityConfiguration.class)
class ApiSecurityTest {
    private static final String CONFIRM = """
        {"decision":"CONFIRM","expectedVersion":0,"note":"已核对官方原文"}
        """;

    @Autowired MockMvc mvc;
    @MockBean ReviewService service;

    @Test
    void anonymousCallerCannotReadOrResolveReviews() throws Exception {
        mvc.perform(get("/api/v1/reviews")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/reviews/" + ApiTestFixtures.REVIEW_ID))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/reviews/" + ApiTestFixtures.REVIEW_ID + "/actions")
                .contentType(MediaType.APPLICATION_JSON).content(CONFIRM))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "viewer", roles = "VIEWER")
    void anAuthenticatedCallerWithoutTheReviewerRoleCannotResolveAReview() throws Exception {
        mvc.perform(post("/api/v1/reviews/" + ApiTestFixtures.REVIEW_ID + "/actions")
                .contentType(MediaType.APPLICATION_JSON).content(CONFIRM))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "viewer", roles = "VIEWER")
    void deletingIsRestrictedToAdministrators() throws Exception {
        mvc.perform(delete("/api/v1/jobs/" + ApiTestFixtures.REVIEW_ID))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "reviewer-a", roles = "REVIEWER")
    void theRecordedActorComesFromTheAuthenticatedPrincipalNotTheRequestBody() throws Exception {
        when(service.act(any())).thenReturn(ApiTestFixtures.details());

        mvc.perform(post("/api/v1/reviews/" + ApiTestFixtures.REVIEW_ID + "/actions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"decision":"CONFIRM","expectedVersion":0,"note":"已核对","actor":"someone-else"}
                    """))
            .andExpect(status().isOk());

        ArgumentCaptor<ApplyReviewActionCommand> command =
            ArgumentCaptor.forClass(ApplyReviewActionCommand.class);
        verify(service).act(command.capture());
        // 请求体里自称的 actor 必须被忽略，否则任何人都能在审计记录里署别人的名字。
        assertThat(command.getValue().actor()).isEqualTo("reviewer-a");
    }

    @Test
    void theLaterApiSurfaceIsCoveredByTheSameDefaultDeny() throws Exception {
        // 采集、决策和个人规划接口是在鉴权之后才长出来的，它们靠 "/api/**" 这条兜底规则受保护。
        // 这个切片没有映射它们：401 说明请求在进入路由前就被拒了；
        // 如果哪天有人给某个前缀加了 permitAll，这里会变成 404 而不是 401。
        mvc.perform(get("/api/acquisition/sources")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/personal/actions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/decisions")).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticationSurvivesRequestsIssuedFromVirtualThreads() throws Exception {
        // 并发用例在 newVirtualThreadPerTaskExecutor 派生的线程里发请求。
        // @WithMockUser 走的是 ThreadLocal，虚拟线程不继承，那些请求会拿 401；
        // TestSecurityDefaults 把认证附在请求上，与发起线程无关。
        when(service.find(ApiTestFixtures.REVIEW_ID)).thenReturn(ApiTestFixtures.details());

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var fromVirtualThread = executor.submit(() -> mvc
                .perform(get("/api/v1/reviews/" + ApiTestFixtures.REVIEW_ID)
                    .with(user(TestSecurityDefaults.OPERATOR)
                        .roles(SecurityConfiguration.ADMIN, SecurityConfiguration.REVIEWER)))
                .andReturn().getResponse().getStatus());
            assertThat(fromVirtualThread.get()).isEqualTo(200);
        }
    }

    @Test
    void livenessProbeIsNotBlockedBySecurity() throws Exception {
        // 这个切片没有映射 actuator，所以拿到 404 而不是 200。
        // 关键在于它不是 401：请求穿过了安全过滤链，编排系统的存活探针不需要凭证。
        mvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
    }
}
