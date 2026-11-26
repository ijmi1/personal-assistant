package assistant.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
class ApiRateLimits {
    private static final long WINDOW = TimeUnit.MINUTES.toNanos(1);
    private final Cache<String, Counter> owners = counters(64);
    private final Cache<String, Counter> anonymous = counters(4096);
    private final Cache<String, Counter> logins = counters(4096);
    private final AuthenticationTrustResolverImpl trust = new AuthenticationTrustResolverImpl();
    private final int ownerLimit;
    private final int anonymousLimit;
    private final int loginLimit;

    ApiRateLimits(@Value("${assistant.api-limit.owner-per-minute}") int ownerLimit,
                  @Value("${assistant.api-limit.anonymous-per-minute}") int anonymousLimit,
                  @Value("${assistant.api-limit.login-per-minute}") int loginLimit) {
        if (Math.min(ownerLimit, Math.min(anonymousLimit, loginLimit)) < 1) {
            throw new IllegalArgumentException("API 和登录限流额度必须大于零");
        }
        this.ownerLimit = ownerLimit;
        this.anonymousLimit = anonymousLimit;
        this.loginLimit = loginLimit;
    }

    OncePerRequestFilter filter(ObjectMapper mapper, String loginPath) {
        return new OncePerRequestFilter() {
            @Override protected boolean shouldNotFilter(HttpServletRequest request) {
                return !request.getServletPath().startsWith("/api/") || request.getMethod().equals("OPTIONS");
            }

            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                       FilterChain chain) throws IOException, ServletException {
                int retry = consume(request, loginPath);
                if (retry == 0) {
                    chain.doFilter(request, response);
                    return;
                }
                response.setHeader(HttpHeaders.RETRY_AFTER, Integer.toString(retry));
                SessionConfig.json(mapper, response, 429, "请求过于频繁，请稍后重试");
            }
        };
    }

    private int consume(HttpServletRequest request, String loginPath) {
        long now = System.nanoTime();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (request.getMethod().equals("POST") && request.getServletPath().equals(loginPath)) {
            return logins.get(request.getRemoteAddr(), key -> new Counter(now)).take(loginLimit, now);
        }
        if (trust.isAuthenticated(authentication)) {
            return owners.get(authentication.getName(), key -> new Counter(now)).take(ownerLimit, now);
        }
        return anonymous.get(request.getRemoteAddr(), key -> new Counter(now)).take(anonymousLimit, now);
    }

    private static Cache<String, Counter> counters(long size) {
        return Caffeine.newBuilder().maximumSize(size).expireAfterAccess(Duration.ofMinutes(2)).build();
    }

    static final class Counter {
        private long start;
        private int used;

        Counter(long start) { this.start = start; }

        synchronized int take(int limit, long now) {
            long elapsed = Math.max(0, now - start);
            if (elapsed >= WINDOW) {
                start = now;
                elapsed = 0;
                used = 0;
            }
            if (used < limit) {
                used++;
                return 0;
            }
            return (int) Math.ceil((WINDOW - elapsed) / 1_000_000_000.0);
        }
    }
}
