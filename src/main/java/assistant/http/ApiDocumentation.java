package assistant.http;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(servers = @Server(url = "/"), info = @Info(title = "个人学习助手 API", version = "v1",
        description = """
                业务接口使用 JSESSIONID 会话。通过 POST /api/v1/session/login 提交 username 和 password 表单字段登录，
                通过 POST /api/v1/session/logout 退出。登录与写操作均需从 GET /api/v1/session/csrf 获取
                headerName 和 token，并将令牌放入对应请求头。登录后重新获取令牌。
                """))
@SecurityScheme(name = "session", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.COOKIE, paramName = "JSESSIONID")
class ApiDocumentation {
    static final String IDEMPOTENCY_KEY = "同一请求重试时复用此键。新的操作使用新键，同键提交不同输入会返回 409。";
}
