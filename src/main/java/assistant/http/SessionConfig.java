package assistant.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Map;
import java.util.regex.Pattern;

@Configuration
public class SessionConfig {
    private static final Logger AUDIT = LoggerFactory.getLogger("assistant.audit");
    private static final Pattern STRONG_PASSWORD = Pattern.compile(
            "(?s)(?=.*\\p{Lu})(?=.*\\p{Ll})(?=.*\\p{Nd})(?=.*[\\p{P}\\p{S}]).{12,}");

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService owner(@Value("${assistant.owner}") String name, @Value("${assistant.password}") String password,
                             @Value("${assistant.allow-legacy-password}") boolean allowLegacy,
                             PasswordEncoder encoder) {
        if (name.isBlank() || password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("请配置账户和有效的 ASSISTANT_PASSWORD");
        }
        if (!allowLegacy && !STRONG_PASSWORD.matcher(password).matches()) {
            throw new IllegalStateException("ASSISTANT_PASSWORD 至少 12 个字符，需包含大小写字母、数字和符号");
        }
        AUDIT.atInfo().addKeyValue("legacy_password_allowed", allowLegacy).log("security.configuration_loaded");
        return new InMemoryUserDetailsManager(User.withUsername(name).password(encoder.encode(password)).roles("OWNER").build());
    }

    @Bean
    @Order(1)
    SecurityFilterChain versionedSecurity(HttpSecurity http, ObjectMapper mapper, ApiRateLimits limits,
                                          UrlBasedCorsConfigurationSource cors) throws Exception {
        return security(http.securityMatcher("/api/v1/**"), mapper, limits, cors, "/api/v1");
    }

    @Bean
    @Order(2)
    SecurityFilterChain legacyAndWebSecurity(HttpSecurity http, ObjectMapper mapper, ApiRateLimits limits,
                                             UrlBasedCorsConfigurationSource cors) throws Exception {
        return security(http, mapper, limits, cors, "/api");
    }

    private SecurityFilterChain security(HttpSecurity http, ObjectMapper mapper, ApiRateLimits limits,
                                         UrlBasedCorsConfigurationSource cors, String prefix) throws Exception {
        String loginPath = prefix + "/session/login";
        var trust = new AuthenticationTrustResolverImpl();
        return http.authorizeHttpRequests(auth -> auth
                        .requestMatchers(prefix + "/session/csrf", loginPath).permitAll()
                        .requestMatchers(EndpointRequest.to("health")).permitAll()
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).hasRole("OWNER")
                        .requestMatchers("/api/**", "/actuator/**", "/v3/api-docs/**", "/v3/api-docs.yaml",
                                "/swagger-ui.html", "/swagger-ui/**").hasRole("OWNER")
                        .anyRequest().permitAll())
                .cors(config -> config.configurationSource(cors))
                .addFilterBefore(limits.filter(mapper, loginPath), CsrfFilter.class)
                .headers(headers -> headers.contentSecurityPolicy(policy -> policy.policyDirectives("""
                        default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline';
                        worker-src 'self' blob:; img-src 'self' data: blob:; font-src 'self' data: blob:;
                        connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'
                        """.replace('\n', ' ').strip())))
                .formLogin(login -> login.loginPage("/").loginProcessingUrl(loginPath)
                        .successHandler((request, response, auth) -> {
                            AUDIT.atInfo().addKeyValue("owner", auth.getName()).log("login.success");
                            response.setStatus(204);
                        })
                        .failureHandler((request, response, error) -> {
                            AUDIT.warn("login.rejected");
                            json(mapper, response, 401, "用户名或密码不正确");
                        }))
                .logout(logout -> logout.logoutUrl(prefix + "/session/logout")
                        .logoutSuccessHandler((request, response, auth) -> response.setStatus(204)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> json(mapper, response, 401, "请先登录"))
                        .accessDeniedHandler((request, response, error) -> {
                            var authentication = SecurityContextHolder.getContext().getAuthentication();
                            boolean anonymous = !trust.isAuthenticated(authentication) && !request.getServletPath().equals(loginPath);
                            json(mapper, response, anonymous ? 401 : 403, anonymous ? "请先登录" : "请求校验失败，请刷新后重试");
                        }))
                .build();
    }

    static void json(ObjectMapper mapper, HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getWriter(), Map.of("message", message));
    }
}

@RestController
@RequestMapping({"/api/session", "/api/v1/session"})
@Tag(name = "会话", description = "获取当前会话身份与 CSRF 令牌")
class SessionRoutes {
    @GetMapping("/csrf")
    @Operation(summary = "获取 CSRF 令牌",
            description = "响应包含 token 和 headerName。登录前及登录成功后各获取一次。")
    Map<String, String> csrf(@Parameter(hidden = true) CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @GetMapping
    @Operation(summary = "查看当前登录用户", description = "返回已登录用户的用户名。")
    Map<String, String> session(@Parameter(hidden = true) Principal owner) {
        return Map.of("username", owner.getName());
    }
}
