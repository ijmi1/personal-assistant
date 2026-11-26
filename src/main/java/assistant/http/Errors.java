package assistant.http;

import assistant.common.Problem;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "assistant.http")
public class Errors {
    @ExceptionHandler(Problem.class)
    ResponseEntity<ErrorBody> problem(Problem error) {
        return ResponseEntity.status(error.status()).body(new ErrorBody(error.getMessage(), error.current()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorBody> invalid(Exception error) {
        return problem(Problem.invalid("请求内容缺失或格式不正确"));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ErrorBody> internal(RuntimeException error) {
        LoggerFactory.getLogger(Errors.class).error("请求处理失败: {}", error.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(new ErrorBody("服务暂时无法处理请求", null));
    }

    record ErrorBody(String message, Object current) {}
}
