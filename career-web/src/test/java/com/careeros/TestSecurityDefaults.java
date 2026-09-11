package com.careeros;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 给 MockMvc 的每个请求附带一个已认证主体。
 *
 * 不能用 {@code @WithMockUser}：它把认证放进测试线程的 SecurityContextHolder，
 * 而并发用例是在 {@code newVirtualThreadPerTaskExecutor} 派生的线程里发请求的，
 * 虚拟线程不会继承这个 ThreadLocal，那些请求会拿到 401。
 *
 * defaultRequest 的 RequestPostProcessor 会合并进每个 perform 的请求本身，
 * 与发起线程无关。
 */
@TestConfiguration(proxyBeanMethods = false)
class TestSecurityDefaults {
    static final String OPERATOR = "test-operator";

    @Bean
    MockMvcBuilderCustomizer authenticatedByDefault() {
        return builder -> builder.defaultRequest(
            get("/").with(user(OPERATOR).roles(SecurityConfiguration.ADMIN, SecurityConfiguration.REVIEWER)));
    }
}
