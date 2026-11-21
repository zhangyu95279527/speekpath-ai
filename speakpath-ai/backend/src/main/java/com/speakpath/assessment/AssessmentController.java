package com.speakpath.assessment;

import com.speakpath.common.api.ApiEnvelope;
import com.speakpath.common.idempotency.Idempotent;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 测评 API：创建、提交回答（幂等）、状态、结束（202+jobId）、报告。
 */
@RestController
@RequestMapping("/api/v1/assessment")
public class AssessmentController {

    private final AssessmentOrchestrator orchestrator;

    public AssessmentController(AssessmentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    public record CreateSessionRequest(String examCode, BigDecimal targetScore, String locale, String inputMode) {
    }

    @PostMapping("/sessions")
    public ApiEnvelope<Map<String, Object>> create(@RequestBody CreateSessionRequest request) {
        return ApiEnvelope.ok(orchestrator.start(
                request.examCode() == null ? "IELTS_SPEAKING" : request.examCode(),
                request.targetScore(), request.inputMode() == null ? "AUDIO" : request.inputMode(),
                request.locale()));
    }

    @GetMapping("/sessions")
    public ApiEnvelope<List<Map<String, Object>>> list() {
        return ApiEnvelope.ok(orchestrator.recentSessions());
    }

    @Idempotent
    @PostMapping("/sessions/{id}/turns")
    public ApiEnvelope<Map<String, Object>> submitTurn(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return ApiEnvelope.ok(orchestrator.submitTurn(id, body));
    }

    @GetMapping("/sessions/{id}")
    public ApiEnvelope<Map<String, Object>> status(@PathVariable UUID id) {
        return ApiEnvelope.ok(orchestrator.status(id));
    }

    @PostMapping("/sessions/{id}/finish")
    public ApiEnvelope<Map<String, Object>> finish(@PathVariable UUID id) {
        return ApiEnvelope.ok(orchestrator.finish(id));
    }

    @GetMapping("/sessions/{id}/report")
    public ApiEnvelope<Map<String, Object>> reportBySession(@PathVariable UUID id) {
        return ApiEnvelope.ok(orchestrator.reportBySession(id));
    }

    @GetMapping("/reports/{id}")
    public ApiEnvelope<Map<String, Object>> report(@PathVariable UUID id) {
        return ApiEnvelope.ok(orchestrator.getReport(id));
    }
}
