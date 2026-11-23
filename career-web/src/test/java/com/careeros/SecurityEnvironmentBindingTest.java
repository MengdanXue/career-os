package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * 部署文档里写的环境变量名必须真的绑得上。
 *
 * <p>README 曾写成 {@code CAREER_OS_SECURITY_USERS_0_PASSWORDHASH}：用户名和角色都绑上了，
 * 口令哈希却是 null，应用启动即报 "password-hash is required"。照文档部署的人第一步就失败，
 * 而 {@link ConfiguredCredentialsTest} 用的是属性写法，照不出环境变量这一层。
 */
class SecurityEnvironmentBindingTest {

    private static SecurityConfiguration.SecurityUsers bind(Map<String, Object> variables) {
        var environment = new StandardEnvironment();
        // 名字必须是 systemEnvironment，Spring 才会按环境变量的规则映射。
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        return Binder.get(environment).bind("career-os.security", SecurityConfiguration.SecurityUsers.class)
            .orElseThrow(() -> new AssertionError("career-os.security 没有绑定到任何值"));
    }

    @Test void theDocumentedVariableNamesBindAllThreeFields() {
        var users = bind(Map.of(
            "CAREER_OS_SECURITY_USERS_0_USERNAME", "zhang",
            "CAREER_OS_SECURITY_USERS_0_PASSWORD_HASH", "$2y$12$abcdefghijklmnopqrstuv",
            "CAREER_OS_SECURITY_USERS_0_ROLES_0", "REVIEWER"));

        var user = users.users().getFirst();
        assertThat(user.username()).isEqualTo("zhang");
        assertThat(user.passwordHash()).isEqualTo("$2y$12$abcdefghijklmnopqrstuv");
        assertThat(user.roles()).containsExactly("REVIEWER");
    }

    /** 钉住那个错误写法的后果，免得有人再把它写回文档。 */
    @Test void withoutTheUnderscoreThePasswordHashDoesNotBind() {
        var users = bind(Map.of(
            "CAREER_OS_SECURITY_USERS_0_USERNAME", "zhang",
            "CAREER_OS_SECURITY_USERS_0_PASSWORDHASH", "$2y$12$abcdefghijklmnopqrstuv",
            "CAREER_OS_SECURITY_USERS_0_ROLES_0", "REVIEWER"));

        assertThat(users.users().getFirst().passwordHash()).isNull();
    }
}
