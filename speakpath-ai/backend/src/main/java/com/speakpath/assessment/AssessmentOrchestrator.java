package com.speakpath.assessment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.speakpath.ai.gateway.AiCapability;
import com.speakpath.ai.gateway.AiGateway;
import com.speakpath.ai.speech.SpeechGateway;
import com.speakpath.ai.speech.SpeechRequest;
import com.speakpath.ai.speech.SpeechResult;
import com.speakpath.common.api.ApiException;
import com.speakpath.common.api.ErrorCode;
import com.speakpath.common.security.CurrentUser;
import com.speakpath.exam.ExamCatalogService;
import com.speakpath.exam.ExamTemplate;
import com.speakpath.exam.QuestionBankItem;
import com.speakpath.identity.AuthService;
import com.speakpath.infrastructure.events.EventLogger;
import com.speakpath.infrastructure.jobs.JobRecord;
import com.speakpath.infrastructure.jobs.JobService;
import com.speakpath.infrastructure.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 测评用例编排：创建会话、提交回答、状态查询、结束测评（异步报告）。
 */
@Service
public class AssessmentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AssessmentOrchestrator.class);
    public static final int MIN_TURNS = 10;

    private final AssessmentSessionRepository sessionRepository;
    private final AssessmentTurnRepository turnRepository;
    private final AssessmentReportRepository reportRepository;
    private final ExamCatalogService examCatalog;
    private final AdaptiveQuestionSelector selector;
    private final AnswerAnalysisService analysisService;
    private final ReportGenerationService reportGeneration;
    private final JobService jobService;
    private final StorageService storage;
    private final AuthService authService;
    private final AiGateway aiGateway;
    private final SpeechGateway speechGateway;
    private final EventLogger eventLogger;
    private final ObjectMapper mapper = new ObjectMapper();

    public AssessmentOrchestrator(AssessmentSessionRepository sessionRepository,
                                  AssessmentTurnRepository turnRepository,
                                  AssessmentReportRepository reportRepository,
                                  ExamCatalogService examCatalog, AdaptiveQuestionSelector selector,
                                  AnswerAnalysisService analysisService,
                                  ReportGenerationService reportGeneration,
                                  JobService jobService, StorageService storage,
                                  AuthService authService, AiGateway aiGateway,
                                  SpeechGateway speechGateway, EventLogger eventLogger) {
        this.sessionRepository = sessionRepository;
        this.turnRepository = turnRepository;
        this.reportRepository = reportRepository;
        this.examCatalog = examCatalog;
        this.selector = selector;
        this.analysisService = analysisService;
        this.reportGeneration = reportGeneration;
        this.jobService = jobService;
        this.storage = storage;
        this.authService = authService;
        this.aiGateway = aiGateway;
        this.speechGateway = speechGateway;
        this.eventLogger = eventLogger;
    }

    @Transactional
    public Map<String, Object> start(String examCode, BigDecimal targetScore, String inputMode, String locale) {
        UUID userId = CurrentUser.require().id();
        if ("AUDIO".equalsIgnoreCase(inputMode) && !authService.hasAudioConsent(userId)) {
            throw ApiException.forbidden("Audio processing consent is required for audio input mode");
        }
        ExamTemplate template = examCatalog.publishedTemplate(examCode);
        JsonNode config = read(template.getConfigJson());
        short maxTurns = (short) config.path("maxTurns").asInt(15);
        AssessmentSession session = new AssessmentSession(userId, template.getId(), template.getExamCode(),
                template.getVersion(), template.getVersion(), targetScore, maxTurns);
        session.transition(AssessmentSession.IN_PROGRESS);
        sessionRepository.save(session);

        List<QuestionBankItem> pool = examCatalog.questions(examCode);
        QuestionBankItem first = selector.selectNext(pool, Set.of(), 1, selector.sectionOrder(pool));
        Map<String, Object> firstQuestion = questionDto(first, (short) 1, config);

        eventLogger.log(userId, "ASSESSMENT_STARTED",
                Map.of("sessionId", session.getId().toString(), "examCode", examCode, "inputMode", inputMode));

        return Map.of(
                "sessionId", session.getId().toString(),
                "status", session.getStatus(),
                "examCode", examCode,
                "minTurns", config.path("minTurns").asInt(MIN_TURNS),
                "defaultTurns", config.path("defaultTurns").asInt(12),
                "maxTurns", (int) maxTurns,
                "expiresAt", session.getExpiresAt().toString(),
                "firstQuestion", firstQuestion);
    }

    @Transactional
    public Map<String, Object> submitTurn(UUID sessionId, Map<String, Object> body) {
        UUID userId = CurrentUser.require().id();
        AssessmentSession session = owned(sessionId, userId);
        if (!AssessmentSession.IN_PROGRESS.equals(session.getStatus())) {
            throw ApiException.invalid("Session is not in progress", "sessionId", "status=" + session.getStatus());
        }
        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(Instant.now())) {
            session.transition(AssessmentSession.FAILED);
            sessionRepository.save(session);
            throw ApiException.invalid("Session expired", "sessionId", "expired");
        }
        int sequence = body.get("sequence") instanceof Number n ? n.intValue() : -1;
        if (sequence != session.getTurnCount() + 1) {
            throw ApiException.invalid("Unexpected sequence, expected " + (session.getTurnCount() + 1),
                    "sequence", String.valueOf(sequence));
        }
        if (session.getTurnCount() >= session.getMaxTurns()) {
            throw ApiException.invalid("Maximum turns reached", "sequence", "maxTurns=" + session.getMaxTurns());
        }

        // 幂等：同一 session+sequence 已存在则直接返回
        Optional<AssessmentTurn> existing = turnRepository.findBySessionIdAndSequenceNo(sessionId, (short) sequence);
        if (existing.isPresent()) {
            return Map.of("turnId", existing.get().getId().toString(), "status", existing.get().getStatus(),
                    "duplicate", true);
        }

        String answerText = body.get("answerText") instanceof String s ? s : null;
        String audioKey = body.get("audioObjectKey") instanceof String s && !s.isBlank() ? s : null;
        Integer durationMs = body.get("durationMs") instanceof Number n ? n.intValue() : null;
        if (audioKey != null) {
            storage.validateUserPrefix(audioKey);
        }
        if ((answerText == null || answerText.isBlank()) && audioKey == null) {
            throw ApiException.invalid("Either answerText or audioObjectKey is required", "answerText", "required");
        }

        // 本轮题目：优先使用客户端回传的题目信息；否则按选题算法确定性重放（可复现审计）
        UUID questionId = null;
        String questionSnapshot;
        if (body.get("questionId") instanceof String qid) {
            questionId = UUID.fromString(qid);
            questionSnapshot = json(Map.of(
                    "question", body.getOrDefault("question", ""),
                    "section", body.getOrDefault("section", ""),
                    "difficulty", body.getOrDefault("difficulty", 5)));
        } else {
            List<QuestionBankItem> pool = examCatalog.questions(session.getExamCode());
            Set<UUID> used = new HashSet<>(turnRepository.findBySessionIdOrderBySequenceNoAsc(sessionId)
                    .stream().map(AssessmentTurn::getQuestionId).filter(Objects::nonNull).toList());
            QuestionBankItem replay = selector.selectNext(pool, used, sequence, selector.sectionOrder(pool));
            questionId = replay.getId();
            questionSnapshot = json(Map.of("question", replay.getQuestionText(),
                    "section", replay.getSectionCode(), "difficulty", (int) replay.getDifficulty()));
        }

        AnswerAnalysisService.Analysis analysis = analysisService.analyze(durationMs, audioKey, answerText);
        short seq = (short) sequence;
        AssessmentTurn turn = new AssessmentTurn(sessionId, seq, questionId, questionSnapshot,
                answerText, audioKey, durationMs);
        turn.setAudioQualityJson(analysisService.toJson(analysis.quality()));
        turn.setAnalysisJson(analysisService.toJson(analysis.features()));
        String transcriptJson = transcribe(audioKey, answerText, userId, sessionId);
        // 切题判断：题目 vs 用户转写文本，结果并入 transcriptJson
        String questionText = read(questionSnapshot).path("question").asText("");
        String answerForCheck = read(transcriptJson).path("text").asText("");
        JsonNode relevance = relevanceFor(questionText, answerForCheck, userId, sessionId);
        if (relevance != null) {
            ObjectNode merged = (ObjectNode) read(transcriptJson);
            merged.set("relevance", relevance);
            transcriptJson = merged.toString();
        }
        turn.setTranscriptJson(transcriptJson);
        turnRepository.save(turn);
        session.incrementTurnCount();
        sessionRepository.save(session);

        eventLogger.log(userId, "ASSESSMENT_TURN_SUBMITTED",
                Map.of("sessionId", sessionId.toString(), "sequence", sequence));

        // 下一题
        Map<String, Object> nextQuestion = null;
        boolean canFinish = session.getTurnCount() >= MIN_TURNS;
        if (session.getTurnCount() < session.getMaxTurns()) {
            List<QuestionBankItem> pool = examCatalog.questions(session.getExamCode());
            Set<UUID> used = new HashSet<>(turnRepository.findBySessionIdOrderBySequenceNoAsc(sessionId)
                    .stream().map(AssessmentTurn::getQuestionId).filter(Objects::nonNull).toList());
            QuestionBankItem next = selector.selectNext(pool, used, session.getTurnCount() + 1,
                    selector.sectionOrder(pool));
            nextQuestion = questionDto(next, (short) (session.getTurnCount() + 1), read(examCatalog.publishedTemplate(session.getExamCode()).getConfigJson()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("turnId", turn.getId().toString());
        result.put("status", "PROCESSING");
        result.put("turnCount", (int) session.getTurnCount());
        result.put("canFinish", canFinish);
        if (nextQuestion != null) {
            result.put("nextQuestion", nextQuestion);
        } else {
            result.put("nextQuestion", null);
            result.put("maxTurnsReached", true);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(UUID sessionId) {
        AssessmentSession session = owned(sessionId, CurrentUser.require().id());
        Map<String, Object> question = null;
        // 刷新/断线恢复：按选题算法确定性重放当前题目（ASMT-004/ASMT-009）
        if (AssessmentSession.IN_PROGRESS.equals(session.getStatus())
                && session.getTurnCount() < session.getMaxTurns()) {
            List<QuestionBankItem> pool = examCatalog.questions(session.getExamCode());
            Set<UUID> used = new HashSet<>(turnRepository.findBySessionIdOrderBySequenceNoAsc(sessionId)
                    .stream().map(AssessmentTurn::getQuestionId).filter(Objects::nonNull).toList());
            QuestionBankItem current = selector.selectNext(pool, used, session.getTurnCount() + 1,
                    selector.sectionOrder(pool));
            question = questionDto(current, (short) (session.getTurnCount() + 1),
                    read(examCatalog.publishedTemplate(session.getExamCode()).getConfigJson()));
        }
        return Map.of(
                "sessionId", session.getId().toString(),
                "status", session.getStatus(),
                "turnCount", (int) session.getTurnCount(),
                "minTurns", MIN_TURNS,
                "maxTurns", (int) session.getMaxTurns(),
                "currentSequence", session.getTurnCount() + 1,
                "canFinish", session.getTurnCount() >= MIN_TURNS,
                "examCode", session.getExamCode(),
                "expiresAt", session.getExpiresAt() == null ? "" : session.getExpiresAt().toString(),
                "currentQuestion", question);
    }

    /**
     * 结束测评：至少 10 轮（TC-ASMT-002），异步生成报告。
     */
    @Transactional
    public Map<String, Object> finish(UUID sessionId) {
        UUID userId = CurrentUser.require().id();
        AssessmentSession session = owned(sessionId, userId);
        if (!AssessmentSession.IN_PROGRESS.equals(session.getStatus())) {
            throw ApiException.invalid("Session is not in progress", "sessionId", "status=" + session.getStatus());
        }
        if (session.getTurnCount() < MIN_TURNS) {
            throw ApiException.invalid("At least " + MIN_TURNS + " turns are required before finishing",
                    "turnCount", String.valueOf(session.getTurnCount()));
        }
        session.transition(AssessmentSession.PROCESSING);
        sessionRepository.save(session);

        ExamTemplate template = examCatalog.publishedTemplate(session.getExamCode());
        JobRecord job = jobService.create("ASSESSMENT_REPORT", null);
        UUID sessionIdCopy = session.getId();
        jobService.runAsync(job, () -> {
            UUID reportId = reportGeneration.generate(sessionIdCopy, template);
            return Map.entry("report:" + reportId, "{\"reportId\":\"" + reportId + "\"}");
        });
        return Map.of("jobId", job.getId().toString(), "status", "QUEUED");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getReport(UUID reportId) {
        AssessmentReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> ApiException.notFound("Report"));
        AssessmentSession session = sessionRepository.findById(report.getSessionId())
                .orElseThrow(() -> ApiException.notFound("Assessment session"));
        if (!session.getUserId().equals(CurrentUser.require().id())) {
            throw ApiException.forbidden("Report does not belong to current user");
        }
        return mapper.convertValue(read(report.getReportJson()),
                new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                });
    }

    @Transactional(readOnly = true)
    public Map<String, Object> reportBySession(UUID sessionId) {
        AssessmentSession session = owned(sessionId, CurrentUser.require().id());
        AssessmentReport report = reportRepository.findBySessionId(sessionId)
                .orElseThrow(() -> ApiException.notFound("Report not ready yet"));
        Map<String, Object> data = new LinkedHashMap<>(mapper.convertValue(read(report.getReportJson()),
                new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                }));
        data.put("reportId", report.getId().toString());
        data.put("sessionId", sessionId.toString());
        return data;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recentSessions() {
        UUID userId = CurrentUser.require().id();
        return sessionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sessionId", s.getId().toString());
                    m.put("examCode", s.getExamCode());
                    m.put("status", s.getStatus());
                    m.put("turnCount", (int) s.getTurnCount());
                    m.put("createdAt", s.getCreatedAt().toString());
                    reportRepository.findBySessionId(s.getId()).ifPresent(r -> m.put("reportId", r.getId().toString()));
                    return m;
                })
                .toList();
    }

    private AssessmentSession owned(UUID sessionId, UUID userId) {
        AssessmentSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> ApiException.notFound("Assessment session"));
        return session;
    }

    /**
     * 转写：文本直接采用；音频走 ASR 能力（按 enable+provider 配置路由，无密钥回退 mock 并标记低置信）。
     * 音频以字节直传供应商（云端服务无法访问本机 localhost 的预签名 URL）。
     */
    private String transcribe(String audioKey, String answerText, UUID userId, UUID sessionId) {
        if (answerText != null && !answerText.isBlank()) {
            return json(Map.of("text", answerText, "confidence", 1.0, "source", "text"));
        }
        try {
            byte[] audio = storage.download(audioKey);
            SpeechResult sr = speechGateway.invoke(AiCapability.ASR,
                    SpeechRequest.asr(audio, audioFormat(audioKey), null));
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("text", sr.text() == null ? "" : sr.text());
            m.put("confidence", sr.confidence());
            m.put("source", "audio");
            m.put("vendor", sr.vendorCode());
            m.put("model", sr.modelCode() == null ? "" : sr.modelCode());
            if (sr.text() == null || sr.text().isBlank()) {
                m.put("lowConfidence", true);
            }
            return json(m);
        } catch (Exception e) {
            log.warn("ASR failed for turn, marking low confidence: {}", e.getMessage());
            return json(Map.of("text", "", "confidence", 0.1, "lowConfidence", true, "source", "audio"));
        }
    }

    private String audioFormat(String audioKey) {
        int dot = audioKey.lastIndexOf('.');
        if (dot < 0 || dot == audioKey.length() - 1) {
            return "wav";
        }
        return audioKey.substring(dot + 1).toLowerCase();
    }

    /** 切题判断：题目 vs 用户回答，返回 onTopic/relevance/reason/guidance；无文本或失败返回 null。 */
    private JsonNode relevanceFor(String question, String answer, UUID userId, UUID sessionId) {
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
            return null;
        }
        try {
            return aiGateway.invoke(new AiGateway.Invocation(AiCapability.TUTOR,
                            "Evaluate topic relevance. Given the topic and the user's answer, judge whether the answer is on-topic. Reply with JSON only: {\"onTopic\":boolean,\"relevance\":number between 0 and 1,\"reason\":\"string\",\"guidance\":\"string to bring the user back on topic\"}.",
                            Map.of("mode", "relevance", "topic", question, "userAnswer", answer), null,
                            Duration.ofSeconds(15), "relevance-v1"),
                    new AiGateway.ModelInvocationContext(userId, sessionId, null, "STANDARD"))
                    .output();
        } catch (Exception e) {
            log.warn("topic relevance check failed: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> questionDto(QuestionBankItem question, short sequence, JsonNode config) {
        String section = question.getSectionCode();
        int responseSeconds = 45;
        for (JsonNode s : config.path("sections")) {
            if (s.path("code").asText().equals(section)) {
                responseSeconds = s.path("responseSeconds").asInt(responseSeconds);
            }
        }
        return Map.of(
                "questionId", question.getId().toString(),
                "sequence", (int) sequence,
                "question", question.getQuestionText(),
                "section", section,
                "difficulty", (int) question.getDifficulty(),
                "responseTimeSeconds", responseSeconds);
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
