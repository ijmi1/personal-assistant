package assistant.model;

import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenAiConfiguration {
    @Bean
    OpenAiConnectionDetails openAiConnectionDetails(
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key}") String apiKey) {
        // Spring AI 1 added /v1 to the request path. The SDK takes it in the base URL.
        String endpoint = baseUrl.strip().replaceFirst("/+$", "");
        return new Connection(endpoint.endsWith("/v1") ? endpoint : endpoint + "/v1", apiKey);
    }

    private record Connection(String getBaseUrl, String getApiKey) implements OpenAiConnectionDetails {}
}
