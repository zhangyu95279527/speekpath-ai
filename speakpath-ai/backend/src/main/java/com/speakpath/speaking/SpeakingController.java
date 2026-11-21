package com.speakpath.speaking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.speakpath.common.api.ApiEnvelope;
import com.speakpath.common.api.ApiException;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/speaking")
public class SpeakingController {

    private final SpeakingSessionService sessionService;
    private final SpeakingMessageService messageService;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpeakingController(SpeakingSessionService sessionService, SpeakingMessageService messageService) {
        this.sessionService = sessionService;
        this.messageService = messageService;
    }

    public record CreateSessionRequest(UUID taskId, String mode, String locale, int maxDurationSeconds) {
    }

    /** 用户消息入口（文字/语音/发音评测）：同步返回本轮事件，替代原 WebSocket 通道。 */
    @PostMapping("/sessions/{id}/messages")
    public ApiEnvelope<Map<String, Object>> message(@PathVariable UUID id, @RequestBody JsonNode body) {
        String type = body.path("type").asText("user.text");
        List<Map<String, Object>> events;
        if ("user.pronunciation".equals(type)) {
            ObjectNode message = mapper.createObjectNode();
            message.put("type", type);
            message.put("turnId", body.path("turnId").asText(""));
            message.set("result", body.path("result"));
            events = messageService.handlePronunciation(id, message);
        } else if ("user.text".equals(type) || "user.audio".equals(type) || "user.message".equals(type)) {
            events = messageService.handleUserMessage(id, body);
        } else {
            throw ApiException.invalid("Unsupported message type", "type", type);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("events", events);
        return ApiEnvelope.ok(result);
    }

    @PostMapping("/sessions")
    public ApiEnvelope<Map<String, Object>> create(@RequestBody CreateSessionRequest request) {
        return ApiEnvelope.ok(sessionService.create(request.taskId(), request.mode(), request.locale(),
                request.maxDurationSeconds() <= 0 ? 900 : request.maxDurationSeconds()));
    }

    @GetMapping("/sessions")
    public ApiEnvelope<List<Map<String, Object>>> history() {
        return ApiEnvelope.ok(sessionService.history());
    }

    /** 今日口语额度状态：剩余未用完时用户可继续新的情景对话。 */
    @GetMapping("/quota")
    public ApiEnvelope<Map<String, Object>> quota() {
        return ApiEnvelope.ok(sessionService.quotaStatus());
    }

    /** 可续接/可回看的会话（最近未结束的优先）；额度用尽时返回最近会话供只读回看。 */
    @GetMapping("/sessions/resumable")
    public ApiEnvelope<Map<String, Object>> resumable(@RequestParam(required = false) UUID taskId) {
        return ApiEnvelope.ok(sessionService.resumable(taskId));
    }

    /** 重新开始：结束当前会话并按同一任务/话题新建会话。 */
    @PostMapping("/sessions/{id}/restart")
    public ApiEnvelope<Map<String, Object>> restart(@PathVariable UUID id) {
        return ApiEnvelope.ok(sessionService.restart(id));
    }

    /** 会话历史轮次：续接对话时前端恢复消息列表。 */
    @GetMapping("/sessions/{id}/turns")
    public ApiEnvelope<List<Map<String, Object>>> turns(@PathVariable UUID id) {
        return ApiEnvelope.ok(sessionService.turns(id));
    }

    public record CredentialRequest(String clientPlatform, String requestedTransport) {
    }

    @PostMapping("/sessions/{id}/credential")
    public ApiEnvelope<Map<String, Object>> credential(@PathVariable UUID id, @RequestBody CredentialRequest request) {
        return ApiEnvelope.ok(sessionService.credential(id, request.clientPlatform(), request.requestedTransport()));
    }

    public record FinishRequest(String endedBy, String clientLastEventId) {
    }

    @PostMapping("/sessions/{id}/finish")
    public ApiEnvelope<Map<String, Object>> finish(@PathVariable UUID id, @RequestBody(required = false) FinishRequest request) {
        return ApiEnvelope.ok(sessionService.finish(id,
                request == null ? "USER" : request.endedBy(),
                request == null ? null : request.clientLastEventId()));
    }

    @GetMapping("/sessions/{id}/feedback")
    public ApiEnvelope<Map<String, Object>> feedback(@PathVariable UUID id) {
        return ApiEnvelope.ok(sessionService.feedback(id));
    }
}
