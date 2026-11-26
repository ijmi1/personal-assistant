package assistant.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1)
class ApiAccessLog extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger("assistant.access");

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            var route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            LOG.atInfo().addKeyValue("method", request.getMethod()).addKeyValue("status", response.getStatus())
                    .addKeyValue("route", route == null ? "unmatched" : route.toString())
                    .addKeyValue("duration_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .log("api.request");
        }
    }
}
