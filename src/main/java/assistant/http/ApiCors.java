package assistant.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

@Configuration
class ApiCors {
    @Bean
    UrlBasedCorsConfigurationSource apiOrigins(@Value("${assistant.cors-origins}") String configured) {
        var source = new UrlBasedCorsConfigurationSource();
        var origins = Arrays.stream(configured.split(",")).map(String::strip).filter(value -> !value.isEmpty()).toList();
        if (origins.isEmpty()) {
            return source;
        }
        origins.forEach(ApiCors::checkOrigin);
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"));
        cors.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN", "Idempotency-Key"));
        cors.setExposedHeaders(List.of("Retry-After"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(600L);
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    private static void checkOrigin(String origin) {
        var uri = URI.create(origin);
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || !uri.getPath().isEmpty()
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("ASSISTANT_CORS_ORIGINS 只接受精确的 HTTP(S) 来源");
        }
    }
}
