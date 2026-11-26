package assistant.http;

import assistant.common.Problem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api", "/api/v1"})
@Tag(name = "前端诊断", description = "记录网页导航和渲染故障的类型")
class ClientErrorRoutes {
    private static final Logger LOG = LoggerFactory.getLogger(ClientErrorRoutes.class);

    @PostMapping("/client-errors")
    @Operation(summary = "上报前端故障类型", description = "记录故障类型，供运维定位网页问题。")
    @ApiResponse(responseCode = "204", description = "故障类型已记录。")
    ResponseEntity<Void> report(@RequestBody Report report) {
        if (!"navigation".equals(report.kind()) && !"render".equals(report.kind())) {
            throw Problem.invalid("未知的前端错误类型");
        }
        LOG.atWarn().addKeyValue("kind", report.kind()).log("client.error");
        return ResponseEntity.noContent().build();
    }

    record Report(@Schema(description = "故障类型", allowableValues = {"navigation", "render"},
                          example = "navigation") String kind) {}
}
