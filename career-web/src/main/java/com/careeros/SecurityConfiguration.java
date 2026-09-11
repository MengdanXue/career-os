package com.careeros;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 全站默认拒绝。
 *
 * 人工复核是这套系统里唯一把模型提案提升为已核验官方数据的闸门；在加上鉴权之前，
 * 任何能访问到端口的人都可以 POST 一个 CONFIRM 把提案直接写进正式岗位库，
 * 也可以 DELETE 掉任意岗位、单位和候选人。这道闸门必须先有身份才谈得上有效。
 *
 * 用 HTTP Basic + 配置式用户：单人或小团队内部工具的最小可行方案，不引入外部依赖。
 * 将来换 SSO 只需要替换这一个 SecurityFilterChain，其余代码不受影响。
 */
@Configuration
@EnableConfigurationProperties(SecurityConfiguration.SecurityUsers.class)
class SecurityConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfiguration.class);
    static final String REVIEWER = "REVIEWER";
    static final String ADMIN = "ADMIN";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        return http
            // 无会话、无表单的 Basic API：没有可被 CSRF 利用的环境凭证。
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(Customizer.withDefaults())
            .authorizeHttpRequests(requests -> requests
                // 存活探针不能要求凭证，否则编排系统无法判断实例健康。
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // 指标会暴露复核队列深度和抽取量，按运维接口对待。
                .requestMatchers("/actuator/**").hasRole(ADMIN)
                // 删除是不可逆的，只给管理员。
                .requestMatchers(HttpMethod.DELETE, "/api/**").hasRole(ADMIN)
                // 复核动作会把提案写进正式岗位库，必须是复核人或管理员。
                .requestMatchers(HttpMethod.POST, "/api/v1/reviews/*/actions").hasAnyRole(REVIEWER, ADMIN)
                .requestMatchers("/api/**").authenticated()
                .anyRequest().authenticated())
            .build();
    }

    @Bean
    InMemoryUserDetailsManager userDetailsService(SecurityUsers configured, PasswordEncoder encoder) {
        List<UserDetails> users = new ArrayList<>();
        for (SecurityUsers.ConfiguredUser user : configured.users()) {
            // 整条空白的条目视为"未配置"（例如占位的环境变量没有注入），
            // 而不是让应用启动失败；只填了一半才是真正的配置错误。
            if (user.isBlank()) continue;
            users.add(User.withUsername(user.requireUsername())
                .password(user.requirePasswordHash())
                .roles(user.normalizedRoles().toArray(String[]::new))
                .build());
        }
        if (users.isEmpty()) {
            // 没有配置用户时不能悄悄放行，也不该拒绝启动（本地跑一次就卡住）：
            // 生成一次性随机口令并打到日志，行为可预期且不会留下可猜的默认凭证。
            String generated = UUID.randomUUID().toString();
            LOG.warn("""
                No career-os.security.users configured. Generated a one-off ADMIN account \
                for this process only: username 'admin', password '{}'. \
                Set career-os.security.users[0].username / password-hash / roles before deploying.""",
                generated);
            users.add(User.withUsername("admin")
                .password(encoder.encode(generated))
                .roles(ADMIN, REVIEWER)
                .build());
        }
        return new InMemoryUserDetailsManager(users);
    }

    /**
     * 密码以 BCrypt 哈希配置，明文口令不进配置文件也不进仓库。
     * 生成方式：{@code htpasswd -nbBC 12 "" '<password>' | cut -d: -f2}
     */
    @ConfigurationProperties(prefix = "career-os.security")
    record SecurityUsers(List<ConfiguredUser> users) {
        SecurityUsers {
            users = users == null ? List.of() : List.copyOf(users);
        }

        record ConfiguredUser(String username, String passwordHash, List<String> roles) {
            boolean isBlank() {
                return (username == null || username.isBlank())
                    && (passwordHash == null || passwordHash.isBlank())
                    && (roles == null || roles.isEmpty());
            }

            String requireUsername() {
                if (username == null || username.isBlank()) {
                    throw new IllegalStateException("career-os.security.users[].username is required");
                }
                return username;
            }

            String requirePasswordHash() {
                if (passwordHash == null || passwordHash.isBlank()) {
                    throw new IllegalStateException(
                        "career-os.security.users[].password-hash is required for " + username);
                }
                if (!passwordHash.startsWith("$2")) {
                    throw new IllegalStateException(
                        "career-os.security.users[].password-hash must be a BCrypt hash for " + username);
                }
                return "{bcrypt}" + passwordHash;
            }

            Set<String> normalizedRoles() {
                if (roles == null || roles.isEmpty()) {
                    throw new IllegalStateException(
                        "career-os.security.users[].roles is required for " + username);
                }
                Set<String> normalized = new LinkedHashSet<>();
                for (String role : roles) {
                    if (role == null || role.isBlank()) continue;
                    String value = role.trim().toUpperCase(java.util.Locale.ROOT);
                    normalized.add(value.startsWith("ROLE_") ? value.substring("ROLE_".length()) : value);
                }
                if (normalized.isEmpty()) {
                    throw new IllegalStateException(
                        "career-os.security.users[].roles is required for " + username);
                }
                return normalized;
            }
        }
    }
}
