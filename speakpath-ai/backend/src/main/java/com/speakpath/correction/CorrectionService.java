package com.speakpath.correction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speakpath.ai.gateway.AiCapability;
import com.speakpath.ai.gateway.AiGateway;
import com.speakpath.plan.StudyPlanRepository;
import com.speakpath.speaking.SpeakingSession;
import com.speakpath.speaking.SpeakingSessionRepository;
import com.speakpath.speaking.SpeakingTurn;
import com.speakpath.speaking.SpeakingTurnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 纠错聚合（SPK-008）：语法、时态、词汇、搭配、句型、发音；每条反馈含原文证据。
 */
@Service
public class CorrectionService {

    private static final Logger log = LoggerFactory.getLogger(CorrectionService.class);

    private final CorrectionItemRepository correctionRepository;
    private final SpeakingSessionRepository sessionRepository;
    private final SpeakingTurnRepository turnRepository;
    private final StudyPlanRepository planRepository;
    private final AiGateway aiGateway;
    private final ObjectMapper mapper = new ObjectMapper();

    public CorrectionService(CorrectionItemRepository correctionRepository,
                             SpeakingSessionRepository sessionRepository,
                             SpeakingTurnRepository turnRepository,
                             StudyPlanRepository planRepository,
                             AiGateway aiGateway) {
        this.correctionRepository = correctionRepository;
        this.sessionRepository = sessionRepository;
        this.turnRepository = turnRepository;
        this.planRepository = planRepository;
        this.aiGateway = aiGateway;
    }

    /**
     * 课后纠错：汇总用户轮次 → CORRECTION 模型 → 持久化纠错条目与总结。
     *
     * @return {summary, corrections, shadowingTasks}
     */
    @Transactional
    public Map<String, Object> generateFeedback(UUID sessionId) {
        SpeakingSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("Speaking session not found"));
        List<SpeakingTurn> turns = turnRepository.findBySessionIdOrderBySequenceNoAsc(sessionId);
        List<UUID> userTurnIds = turns.stream()
                .filter(t -> "USER".equals(t.getRole()))
                .map(SpeakingTurn::getId).toList();
        List<String> sentences = turns.stream()
                .filter(t -> "USER".equals(t.getRole()) && t.getTranscript() != null && !t.getTranscript().isBlank())
                .map(SpeakingTurn::getTranscript).toList();

        JsonNode output;
        String examCode = examCodeOf(session.getUserId());
        try {
            output = aiGateway.invoke(new AiGateway.Invocation(AiCapability.CORRECTION,
                            "You are an English coach preparing feedback for the " + ExamSpeakingCriteria.examName(examCode)
                                    + " speaking test. For each user sentence, find grammar, tense, vocabulary, collocation and sentence-structure errors, "
                                    + "always quoting the original text as evidence. Classify every correction by the exam's speaking criteria: "
                                    + ExamSpeakingCriteria.descriptions(examCode) + ". "
                                    + "Output a single JSON object with: \"summary\" (string), and \"corrections\" (array of objects each with "
                                    + "\"category\" (one of " + ExamSpeakingCriteria.codes(examCode) + "), \"severity\" (one of LOW/MEDIUM/HIGH), "
                                    + "\"original\" (the user's text), \"corrected\" (the corrected text), \"explanation\" (string), and \"confidence\" (number between 0 and 1)).",
                            Map.of("sentences", sentences, "examCode", examCode), null, Duration.ofSeconds(25), "correction-exam-v1"),
                    new AiGateway.ModelInvocationContext(session.getUserId(), sessionId, null, "STANDARD"))
                    .output();
        } catch (Exception e) {
            log.warn("CORRECTION model failed for session {}: {}", sessionId, e.getMessage());
            return Map.of("summary", "课后反馈生成失败，请稍后重试。", "corrections", List.of(), "shadowingTasks", List.of());
        }

        List<Map<String, Object>> corrections = new ArrayList<>();
        int userTurnIdx = 0;
        for (JsonNode c : output.path("corrections")) {
            UUID turnId = userTurnIdx < userTurnIds.size() ? userTurnIds.get(userTurnIdx) : null;
            String category = ExamSpeakingCriteria.normalize(examCode, c.path("category").asText("EXPRESSION"));
            if (turnId != null) {
                CorrectionItem item = new CorrectionItem(
                        turnId,
                        category,
                        c.path("severity").asText("LOW"),
                        c.path("original").asText(""),
                        c.path("corrected").asText(""),
                        c.path("explanation").asText(""),
                        "{\"source\":\"correction-model\",\"examCode\":\"" + examCode + "\"}",
                        BigDecimal.valueOf(c.path("confidence").asDouble(0.6)));
                correctionRepository.save(item);
            }
            corrections.add(Map.of(
                    "category", category,
                    "severity", c.path("severity").asText("LOW"),
                    "original", c.path("original").asText(""),
                    "corrected", c.path("corrected").asText(""),
                    "explanation", c.path("explanation").asText(""),
                    "confidence", c.path("confidence").asDouble(0.6)));
            userTurnIdx++;
        }

        List<Map<String, Object>> shadowingTasks = corrections.stream()
                .filter(c -> !c.get("corrected").toString().isBlank())
                .limit(5)
                .map(c -> Map.of(
                        "text", c.get("corrected"),
                        "focus", ExamSpeakingCriteria.label(examCode, String.valueOf(c.get("category"))) + " 纠错复述"))
                .toList();

        return Map.of(
                "summary", output.path("summary").asText("练习完成，继续保持！"),
                "corrections", corrections,
                "shadowingTasks", shadowingTasks);
    }

    /** 用户当前计划对应的考试；无计划时按 IELTS 口语标准分类。 */
    private String examCodeOf(UUID userId) {
        return planRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, "ACTIVE")
                .map(plan -> {
                    try {
                        return mapper.readTree(plan.getGoalJson()).path("examCode").asText("IELTS_SPEAKING");
                    } catch (Exception e) {
                        return "IELTS_SPEAKING";
                    }
                })
                .orElse("IELTS_SPEAKING");
    }
}
