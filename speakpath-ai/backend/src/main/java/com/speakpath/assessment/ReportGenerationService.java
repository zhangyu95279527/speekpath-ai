package com.speakpath.assessment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.speakpath.ai.gateway.AiCapability;
import com.speakpath.ai.gateway.AiGateway;
import com.speakpath.common.api.ApiException;
import com.speakpath.common.api.ErrorCode;
import com.speakpath.exam.ExamTemplate;
import com.speakpath.infrastructure.events.EventLogger;
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
 * 报告编排：汇总轮次转写 → ASSESSMENT 模型按锁定 rubric 评分 → 规则校验 → 持久化。
 */
@Service
public class ReportGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ReportGenerationService.class);

    private final AssessmentSessionRepository sessionRepository;
    private final AssessmentTurnRepository turnRepository;
    private final AssessmentReportRepository reportRepository;
    private final AiGateway aiGateway;
    private final EventLogger eventLogger;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReportGenerationService(AssessmentSessionRepository sessionRepository,
                                   AssessmentTurnRepository turnRepository,
                                   AssessmentReportRepository reportRepository,
                                   AiGateway aiGateway, EventLogger eventLogger) {
        this.sessionRepository = sessionRepository;
        this.turnRepository = turnRepository;
        this.reportRepository = reportRepository;
        this.aiGateway = aiGateway;
        this.eventLogger = eventLogger;
    }

    /**
     * 生成报告并持久化；返回 reportId。输出必须通过 schema 校验，否则报告标记失败。
     */
    @Transactional
    public UUID generate(UUID sessionId, ExamTemplate template) {
        AssessmentSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("Assessment session"));
        List<AssessmentTurn> turns = turnRepository.findBySessionIdOrderBySequenceNoAsc(sessionId);

        List<String> transcripts = new ArrayList<>();
        for (AssessmentTurn turn : turns) {
            String transcript = extractTranscript(turn);
            if (!transcript.isBlank()) {
                transcripts.add(transcript);
            }
        }

        JsonNode rubric = read(template.getRubricJson());
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("transcripts", transcripts);
        input.put("rubric", rubric);
        input.put("targetScore", session.getTargetScore() == null ? null : session.getTargetScore().toPlainString());
        input.put("examCode", session.getExamCode());

        JsonNode output;
        try {
            output = aiGateway.invoke(
                    new AiGateway.Invocation(AiCapability.ASSESSMENT,
                            "You are an IELTS speaking examiner. Score the candidate turns against the locked rubric provided in the input. "
                                    + "Output a single JSON object with exactly these fields: "
                                    + "\"overallEstimate\" (object with numeric \"low\" and \"high\" within the rubric score range), "
                                    + "\"confidence\" (number between 0 and 1), "
                                    + "\"dimensions\" (array with one entry per rubric dimension, each having \"code\" matching the rubric, "
                                    + "\"score\" within the rubric score range, \"evidence\" quoting the transcript, and \"comment\"), "
                                    + "\"limitations\" (array of strings), and \"recommendations\" (array of strings). "
                                    + "Never claim this is an official exam score.",
                            input, null, Duration.ofSeconds(30), "assessment-v1"),
                    new AiGateway.ModelInvocationContext(session.getUserId(), sessionId, null, "STANDARD"))
                    .output();
        } catch (Exception e) {
            log.error("Assessment model failed for session {}", sessionId, e);
            session.transition(AssessmentSession.FAILED);
            sessionRepository.save(session);
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "Report generation failed, please retry");
        }

        validateReport(output);
        enrichDimensions((ObjectNode) output, rubric);

        // 汇总切题度：读取每轮 transcript_json.relevance，合并进报告
        Map<String, Object> topicSummary = summarizeRelevance(turns);
        if (topicSummary != null) {
            ((ObjectNode) output).set("topicRelevance", mapper.valueToTree(topicSummary));
        }

        String reportJson = output.toString();
        BigDecimal low = BigDecimal.valueOf(output.path("overallEstimate").path("low").asDouble());
        BigDecimal high = BigDecimal.valueOf(output.path("overallEstimate").path("high").asDouble());
        BigDecimal confidence = BigDecimal.valueOf(output.path("confidence").asDouble());
        String trace = json(Map.of(
                "provider", "routed-by-ai-gateway",
                "capability", "ASSESSMENT",
                "rubricVersion", output.path("rubricVersion").asText(""),
                "generatedAt", java.time.Instant.now().toString()));

        AssessmentReport report = new AssessmentReport(sessionId, session.getRubricVersion(),
                reportJson, low, high, confidence, trace);
        reportRepository.save(report);

        session.transition(AssessmentSession.COMPLETED);
        session.markCompleted();
        sessionRepository.save(session);

        eventLogger.log(session.getUserId(), "ASSESSMENT_REPORT_READY",
                Map.of("sessionId", sessionId.toString(), "reportId", report.getId().toString()));
        return report.getId();
    }

    private String extractTranscript(AssessmentTurn turn) {
        if (turn.getTranscriptJson() != null) {
            JsonNode node = read(turn.getTranscriptJson());
            String text = node.path("text").asText("");
            if (!text.isBlank()) {
                return text;
            }
        }
        return turn.getAnswerText() == null ? "" : turn.getAnswerText();
    }

    /** 汇总切题度：平均 relevance + 离题轮次列表；无切题数据返回 null。 */
    private Map<String, Object> summarizeRelevance(List<AssessmentTurn> turns) {
        List<Map<String, Object>> offTopic = new ArrayList<>();
        double sum = 0;
        int count = 0;
        for (AssessmentTurn turn : turns) {
            if (turn.getTranscriptJson() == null) {
                continue;
            }
            JsonNode node = read(turn.getTranscriptJson());
            JsonNode rel = node.path("relevance");
            if (rel.isObject() && rel.hasNonNull("relevance")) {
                double r = rel.path("relevance").asDouble();
                sum += r;
                count++;
                if (!rel.path("onTopic").asBoolean(true)) {
                    offTopic.add(Map.of(
                            "sequence", (int) turn.getSequenceNo(),
                            "relevance", r,
                            "reason", rel.path("reason").asText(""),
                            "guidance", rel.path("guidance").asText("")));
                }
            }
        }
        if (count == 0) {
            return null;
        }
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("averageRelevance", Math.round((sum / count) * 100.0) / 100.0);
        summary.put("checkedTurns", count);
        summary.put("offTopicCount", offTopic.size());
        summary.put("offTopicTurns", offTopic);
        return summary;
    }

    /**
     * 规则校验（规格书 §8.4 / TC-SCORE-001）：维度完整性、分数范围、置信度范围。
     */
    private void validateReport(JsonNode report) {
        if (!report.hasNonNull("overallEstimate") || !report.path("overallEstimate").has("low")
                || !report.path("overallEstimate").has("high")) {
            throw new ApiException(ErrorCode.AI_OUTPUT_INVALID, "Report missing overallEstimate");
        }
        if (!report.hasNonNull("confidence")) {
            throw new ApiException(ErrorCode.AI_OUTPUT_INVALID, "Report missing confidence");
        }
        double confidence = report.path("confidence").asDouble(-1);
        if (confidence < 0 || confidence > 1) {
            throw new ApiException(ErrorCode.AI_OUTPUT_INVALID, "Confidence out of range");
        }
        if (!report.hasNonNull("dimensions") || !report.path("dimensions").isArray()
                || report.path("dimensions").isEmpty()) {
            throw new ApiException(ErrorCode.AI_OUTPUT_INVALID, "Report missing dimensions");
        }
        for (JsonNode dim : report.path("dimensions")) {
            if (!dim.hasNonNull("code") || !dim.hasNonNull("score")) {
                throw new ApiException(ErrorCode.AI_OUTPUT_INVALID, "Dimension missing code or score");
            }
            double score = dim.path("score").asDouble(-1);
            if (score < 0 || score > 30) {
                throw new ApiException(ErrorCode.AI_OUTPUT_INVALID, "Dimension score out of range");
            }
        }
    }

    /** 用 rubric 的 label 补齐维度显示名，并把维度结构对齐前端 DTO（label/confidence/evidence[]/recommendations[]）。 */
    private void enrichDimensions(ObjectNode report, JsonNode rubric) {
        report.put("rubricVersion", rubric.path("version").asText(""));
        java.util.Map<String, String> labels = new java.util.HashMap<>();
        rubric.path("dimensions").forEach(d ->
                labels.put(d.path("code").asText(""), d.path("label").asText(d.path("code").asText(""))));
        double confidence = report.path("confidence").asDouble(0.9);

        JsonNode dims = report.get("dimensions");
        if (dims == null || !dims.isArray()) {
            return;
        }
        for (JsonNode dim : dims) {
            if (!(dim instanceof ObjectNode d)) {
                continue;
            }
            String code = d.path("code").asText("");
            d.put("label", labels.getOrDefault(code, code));
            if (!d.has("confidence")) {
                d.put("confidence", confidence);
            }

            JsonNode ev = d.get("evidence");
            if (ev == null || !ev.isArray()) {
                ArrayNode arr = mapper.createArrayNode();
                String quote = ev == null ? "" : ev.asText("");
                String note = d.path("comment").asText("");
                if (!quote.isBlank() || !note.isBlank()) {
                    arr.addObject().put("quote", quote).put("note", note);
                }
                d.set("evidence", arr);
            }

            if (!d.has("recommendations") || !d.get("recommendations").isArray()) {
                ArrayNode rec = mapper.createArrayNode();
                String comment = d.path("comment").asText("");
                if (!comment.isBlank()) {
                    rec.add(comment);
                }
                d.set("recommendations", rec);
            }
        }
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON", e);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
