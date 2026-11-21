package com.speakpath.admin;

import com.speakpath.common.api.ApiEnvelope;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 模型管理后台（AI_ADMIN/SYS_ADMIN）：配置、试连、灰度激活、回滚、审计。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AiModelAdminController {

    private final AiModelAdminService adminService;

    public AiModelAdminController(AiModelAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/ai-models")
    public ApiEnvelope<List<Map<String, Object>>> list() {
        return ApiEnvelope.ok(adminService.list());
    }

    @GetMapping("/ai-models/{id}")
    public ApiEnvelope<Map<String, Object>> get(@PathVariable UUID id) {
        return ApiEnvelope.ok(adminService.get(id));
    }

    @PostMapping("/ai-models")
    public ApiEnvelope<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return ApiEnvelope.ok(adminService.create(body));
    }

    @PostMapping("/ai-models/{id}/test")
    public ApiEnvelope<Map<String, Object>> test(@PathVariable UUID id) {
        return ApiEnvelope.ok(adminService.test(id));
    }

    public record ActivateRequest(String reason) {
    }

    @PostMapping("/ai-models/{id}/activate")
    public ApiEnvelope<Map<String, Object>> activate(@PathVariable UUID id,
                                                     @RequestBody(required = false) ActivateRequest request) {
        return ApiEnvelope.ok(adminService.activate(id, request == null ? null : request.reason()));
    }

    public record RollbackRequest(String reason) {
    }

    @PostMapping("/ai-models/{id}/rollback")
    public ApiEnvelope<Map<String, Object>> rollback(@PathVariable UUID id,
                                                     @RequestBody(required = false) RollbackRequest request) {
        return ApiEnvelope.ok(adminService.rollback(id, request == null ? null : request.reason()));
    }

    @PostMapping("/ai-models/{id}/disable")
    public ApiEnvelope<Map<String, Object>> disable(@PathVariable UUID id,
                                                    @RequestBody(required = false) RollbackRequest request) {
        return ApiEnvelope.ok(adminService.disable(id, request == null ? null : request.reason()));
    }

    @GetMapping("/audit-logs")
    public ApiEnvelope<List<Map<String, String>>> auditLogs() {
        return ApiEnvelope.ok(adminService.auditLogs());
    }
}
