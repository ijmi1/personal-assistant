package assistant.http;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Map;

@Configuration
public class SessionConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService owner(@Value("${assistant.owner}") String name, @Value("${assistant.password}") String password,
                             PasswordEncoder encoder) {
        if (name.isBlank() || password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("请配置账户和有效的 ASSISTANT_PASSWORD");
        }
        return new InMemoryUserDetailsManager(User.withUsername(name).password(encoder.encode(password)).roles("OWNER").build());
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/session/csrf", "/api/session/login").permitAll()
                        .requestMatchers("/api/**").hasRole("OWNER")
                        .anyRequest().permitAll())
                .headers(headers -> headers.contentSecurityPolicy(policy -> policy.policyDirectives("""
                        default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline';
                        worker-src 'self' blob:; img-src 'self' data: blob:; font-src 'self' data: blob:;
                        connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'
                        """.replace('\n', ' ').strip())))
                .formLogin(login -> login.loginProcessingUrl("/api/session/login")
                        .successHandler((request, response, auth) -> response.setStatus(204))
                        .failureHandler((request, response, error) -> json(response, 401, "用户名或密码不正确")))
                .logout(logout -> logout.logoutUrl("/api/session/logout")
                        .logoutSuccessHandler((request, response, auth) -> response.setStatus(204)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> json(response, 401, "请先登录"))
                        .accessDeniedHandler((request, response, error) -> {
                            var authentication = SecurityContextHolder.getContext().getAuthentication();
                            boolean anonymous = !new AuthenticationTrustResolverImpl().isAuthenticated(authentication)
                                    && !request.getServletPath().equals("/api/session/login");
                            json(response, anonymous ? 401 : 403, anonymous ? "请先登录" : "请求校验失败，请刷新后重试");
                        }))
                .build();
    }

    private static void json(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}

@RestController
class SessionRoutes {
    @GetMapping("/api/session/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @GetMapping("/api/session")
    Map<String, String> session(Principal owner) {
        return Map.of("username", owner.getName());
    }
}
