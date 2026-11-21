package com.speakpath.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speakpath.ai.gateway.AiCapability;
import com.speakpath.ai.gateway.AiGateway;
import com.speakpath.common.api.ApiException;
import com.speakpath.common.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 跟读评测：保存客户端（阿里云发音引擎）评出的分数，用 LLM 生成改进意见；支持同一内容多次评测。
 */
@Service
public class TaskPracticeService {

    private static final Logger log = LoggerFactory.getLogger(TaskPracticeService.class);

    private final LearningTaskRepository taskRepository;
    private final ContentItemRepository contentRepository;
    private final PronunciationAttemptRepository attemptRepository;
    private final AiGateway aiGateway;
    private final ObjectMapper mapper = new ObjectMapper();

    public TaskPracticeService(LearningTaskRepository taskRepository, ContentItemRepository contentRepository,
                               PronunciationAttemptRepository attemptRepository, AiGateway aiGateway) {
        this.taskRepository = taskRepository;
        this.contentRepository = contentRepository;
        this.attemptRepository = attemptRepository;
        this.aiGateway = aiGateway;
    }

    @Transactional
    public Map<String, Object> submit(UUID taskId, Map<String, Object> body) {
        UUID userId = CurrentUser.require().id();
        LearningTask task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> ApiException.notFound("Task"));
        int itemIndex = body.get("itemIndex") instanceof Number n ? n.intValue() : -1;
        String refText = body.get("refText") == null ? "" : body.get("refText").toString();
        Object resultObj = body.get("result");
        if (itemIndex < 0 || refText.isBlank() || resultObj == null) {
            throw ApiException.invalid("itemIndex, refText and result are required", "itemIndex", "required");
        }
        JsonNode result = mapper.valueToTree(resultObj);
        validateItemIndex(task, itemIndex);

        List<String> suggestions = suggestionsFor(refText, result);
        PronunciationAttempt attempt = new PronunciationAttempt(userId, taskId, (short) itemIndex,
                refText, result.toString(), mapper.valueToTree(suggestions).toString());
        attempt = attemptRepository.save(attempt);

        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("attemptId", attempt.getId().toString());
        dto.put("itemIndex", (int) attempt.getItemIndex());
        dto.put("refText", attempt.getRefText());
        dto.put("result", read(attempt.getResultJson()));
        dto.put("suggestions", read(attempt.getSuggestionsJson()));
        dto.put("createdAt", attempt.getCreatedAt().toString());
        return dto;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> attempts(UUID taskId) {
        UUID userId = CurrentUser.require().id();
        taskRepository.findByIdAndUserId(taskId, userId).orElseThrow(() -> ApiException.notFound("Task"));
        List<Map<String, Object>> list = new ArrayList<>();
        for (PronunciationAttempt a : attemptRepository.findByTaskIdAndUserIdOrderByCreatedAtDesc(taskId, userId)) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("attemptId", a.getId().toString());
            dto.put("itemIndex", (int) a.getItemIndex());
            dto.put("refText", a.getRefText());
            dto.put("result", read(a.getResultJson()));
            dto.put("suggestions", read(a.getSuggestionsJson()));
            dto.put("createdAt", a.getCreatedAt().toString());
            list.add(dto);
        }
        return list;
    }

    /** itemIndex 必须在任务内容项范围内（句子数或词数）。 */
    private void validateItemIndex(LearningTask task, int itemIndex) {
        String itemId = read(task.getContentJson()).path("contentItemId").asText("");
        if (itemId.isBlank()) {
            return;
        }
        try {
            contentRepository.findById(UUID.fromString(itemId)).ifPresent(item -> {
                JsonNode content = read(item.getContentJson());
                int size = 0;
                if (content.hasNonNull("sentences")) {
                    size = content.path("sentences").size();
                } else if (content.hasNonNull("words")) {
                    size = content.path("words").size();
                } else if (content.hasNonNull("items")) {
                    size = content.path("items").size();
                }
                if (size > 0 && itemIndex >= size) {
                    throw ApiException.invalid("itemIndex out of range", "itemIndex",
                            "expected 0.." + (size - 1));
                }
            });
        } catch (IllegalArgumentException ignored) {
            // 非法 contentItemId：跳过范围校验
        }
    }

    /** LLM 生成改进意见；失败回退规则建议。 */
    private List<String> suggestionsFor(String refText, JsonNode result) {
        try {
            JsonNode ai = aiGateway.invoke(new AiGateway.Invocation(AiCapability.TUTOR,
                            "You are a pronunciation coach. Given the reference text and the learner's pronunciation "
                                    + "scores (overall/accuracy/integrity/fluency, each 0-100), give concise actionable "
                                    + "improvement tips in Chinese. Reply with JSON only: "
                                    + "{\"suggestions\": [\"tip1\", \"tip2\", ...]}.",
                            Map.of("mode", "pronunciation-feedback", "refText", refText, "result", result), null,
                            Duration.ofSeconds(20), "pronunciation-feedback-v1"),
                    new AiGateway.ModelInvocationContext(CurrentUser.require().id(), null, null, "STANDARD"))
                    .output();
            List<String> suggestions = new ArrayList<>();
            ai.path("suggestions").forEach(s -> suggestions.add(s.asText("")));
            suggestions.removeIf(String::isBlank);
            if (!suggestions.isEmpty()) {
                return suggestions;
            }
        } catch (Exception e) {
            log.warn("Pronunciation feedback LLM failed, using rule-based fallback: {}", e.getMessage());
        }
        return ruleBasedSuggestions(result);
    }

    private List<String> ruleBasedSuggestions(JsonNode result) {
        List<String> tips = new ArrayList<>();
        double overall = result.path("overall").asDouble(0);
        double accuracy = result.path("accuracy").asDouble(0);
        double fluency = result.path("fluency").asDouble(0);
        double integrity = result.path("integrity").asDouble(0);
        if (overall >= 85) {
            tips.add("很棒！继续保持语调和节奏。");
        } else if (overall >= 60) {
            tips.add("整体不错，注意以下弱项再练一次。");
        } else {
            tips.add("建议先多听几遍示范音频，逐句模仿重音和语调。");
        }
        if (accuracy < 60) {
            tips.add("准确度偏低：注意每个单词的重音与元音发音，可以放慢速度逐词朗读。");
        }
        if (fluency < 60) {
            tips.add("流利度偏低：先按意群划分句子，减少停顿和重复。");
        }
        if (integrity < 60) {
            tips.add("完整度偏低：跟读时尽量把整句读完，不要漏词。");
        }
        return tips;
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }
}
