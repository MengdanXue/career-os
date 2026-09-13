package com.careeros;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.careeros.application.ExtractionPorts.ReviewPage;
import com.careeros.application.ReviewService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 用真实凭证走一遍登录。
 *
 * <p>其余安全用例都用 {@code @WithMockUser} 或 {@code user(...)} 直接注入已认证身份，
 * 这会整个跳过口令编码器——于是下面这个缺陷在全绿的测试下活了很久：
 * 配置进来的哈希带 {@code {bcrypt}} 前缀，而编码器是裸的 {@code BCryptPasswordEncoder}，
 * 它拿到整串 {@code {bcrypt}$2a$...} 判定为非法，凡是按文档配了账号的部署一律 401，
 * 唯一能用的反而是没配账号时生成的临时 admin。
 *
 * <p>所以这里必须发一个带 Authorization 头的真实请求：身份由配置的哈希校验出来，
 * 不是由测试替身给定的。
 */
@WebMvcTest(controllers = ReviewController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = {
    "career-os.security.users[0].username=operator",
    // bcrypt("configured-secret")，与下面的明文口令对应
    "career-os.security.users[0].password-hash=$2a$12$/HVyZ6.hFxi5aswAmxnnUOUzFbYw2Gw34gyHv/hn3wTag6tZ8eOa2",
    "career-os.security.users[0].roles[0]=ADMIN",
})
class ConfiguredCredentialsTest {
    @Autowired MockMvc mvc;
    @MockBean ReviewService service;

    @org.junit.jupiter.api.BeforeEach void stubService() {
        // 认证通过之后才会走到这里；没有桩会以空指针失败，把"认证成功"误报成失败。
        org.mockito.Mockito.when(service.findPage(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(new ReviewPage(java.util.List.of(), 0, 20, 0));
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder()
            .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 按文档配置的账号必须能登录。这一条就是那个缺陷的正面证据：
     * 修复前它拿到 401，日志里只有一句 "Encoded password does not look like BCrypt"。
     */
    @Test void aConfiguredUserCanAuthenticateWithItsPlaintextPassword() throws Exception {
        mvc.perform(get("/api/v1/reviews")
                .header(HttpHeaders.AUTHORIZATION, basic("operator", "configured-secret")))
            .andExpect(status().isOk());
    }

    /** 错误口令必须被拒——否则上面那条"正确口令通过"可能只是因为谁都能进。 */
    @Test void aWrongPasswordIsRejected() throws Exception {
        mvc.perform(get("/api/v1/reviews").header(HttpHeaders.AUTHORIZATION, basic("operator", "wrong")))
            .andExpect(status().isUnauthorized());
    }

    @Test void anUnknownUserIsRejected() throws Exception {
        mvc.perform(get("/api/v1/reviews").header(HttpHeaders.AUTHORIZATION, basic("nobody", "configured-secret")))
            .andExpect(status().isUnauthorized());
    }
}
