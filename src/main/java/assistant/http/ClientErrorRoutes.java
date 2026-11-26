package assistant.http;

import assistant.common.Problem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api", "/api/v1"})
class ClientErrorRoutes {
    private static final Logger LOG = LoggerFactory.getLogger(ClientErrorRoutes.class);

    @PostMapping("/client-errors")
    ResponseEntity<Void> report(@RequestBody Report report) {
        if (!"navigation".equals(report.kind()) && !"render".equals(report.kind())) {
            throw Problem.invalid("未知的前端错误类型");
        }
        LOG.atWarn().addKeyValue("kind", report.kind()).log("client.error");
        return ResponseEntity.noContent().build();
    }

    record Report(String kind) {}
}
