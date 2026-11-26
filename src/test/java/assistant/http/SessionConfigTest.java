package assistant.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionConfigTest {
    @Test void requiresComplexityByDefaultAndAlwaysEnforcesBcryptByteLimit() {
        var config = new SessionConfig();
        var encoder = config.passwordEncoder();
        assertThatThrownBy(() -> config.owner("learner", "short", false, encoder))
                .isInstanceOf(IllegalStateException.class);
        assertThat(config.owner("learner", "short", true, encoder).loadUserByUsername("learner")).isNotNull();
        assertThat(config.owner("learner", "Strong-login1!", false, encoder).loadUserByUsername("learner")).isNotNull();
        assertThatThrownBy(() -> config.owner("learner", "密码".repeat(20), true, encoder))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void serializesQuotesBackslashesAndNewlinesAsOneJsonValue() throws Exception {
        var json = new ObjectMapper();
        var response = new MockHttpServletResponse();
        String message = "登录 \"失败\"\\\n<详情>";

        SessionConfig.json(json, response, 401, message);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(json.readTree(response.getContentAsString()).path("message").asText()).isEqualTo(message);
    }
}
