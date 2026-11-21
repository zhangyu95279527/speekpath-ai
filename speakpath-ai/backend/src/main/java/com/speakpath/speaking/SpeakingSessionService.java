package com.speakpath.speaking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speakpath.ai.config.AiModelConfig;
import com.speakpath.ai.config.AiModelConfigService;
import com.speakpath.ai.gateway.AiCapability;
import com.speakpath.ai.speech.SpeechGateway;
import com.speakpath.ai.speech.SpeechRequest;
import com.speakpath.ai.speech.SpeechResult;
import com.speakpath.common.api.ApiException;
import com.speakpath.common.security.CurrentUser;
import com.speakpath.correction.CorrectionService;
import com.speakpath.identity.AuthService;
import com.speakpath.infrastructure.events.EventLogger;
import com.speakpath.infrastructure.jobs.JobRecord;
import com.speakpath.infrastructure.jobs.JobService;
import com.speakpath.infrastructure.storage.StorageService;
import com.speakpath.plan.LearningTask;
import com.speakpath.plan.LearningTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 实时口语会话：创建、短时凭证、结束（异步反馈）、额度控制。
 * 实时凭证只对单次会话有效；客户端不可指定任意 provider/base URL。
 */
@Service
public class SpeakingSessionService {

    private static final Logger log = LoggerFactory.getLogger(SpeakingSessionService.class);

    private final SpeakingSessionRepository sessionRepository;
    private final SpeakingTurnRepository turnRepository;
    private final AiModelConfigService configService;
    private final SpeechGateway speechGateway;
    private final JobService jobService;
    private final CorrectionService correctionService;
    private final LearningTaskRepository taskRepository;
    private final AuthService authService;
    private final EventLogger eventLogger;
    private final SpeakingMessageService messageService;
    private final com.speakpath.plan.DailyThemeService dailyThemeService;
    private final StorageService storage;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpeakingSessionService(SpeakingSessionRepository sessionRepository,
                                  SpeakingTurnRepository turnRepository,
                                  AiModelConfigService configService, SpeechGateway speechGateway,
                                  JobService jobService,
                                  CorrectionService correctionService,
                                  LearningTaskRepository taskRepository, AuthService authService,
                                  EventLogger eventLogger, SpeakingMessageService messageService,
                                  com.speakpath.plan.DailyThemeService dailyThemeService,
                                  StorageService storage,
                                  StringRedisTemplate redis) {
        this.sessionRepository = sessionRepository;
        this.turnRepository = turnRepository;
        this.configService = configService;
        this.speechGateway = speechGateway;
        this.jobService = jobService;
        this.correctionService = correctionService;
        this.taskRepository = taskRepository;
        this.authService = authService;
        this.eventLogger = eventLogger;
        this.messageService = messageService;
        this.dailyThemeService = dailyThemeService;
        this.storage = storage;
        this.redis = redis;
    }

    @Transactional
    public Map<String, Object> create(UUID taskId, String mode, String locale, int maxDurationSeconds) {
        UUID userId = CurrentUser.require().id();
        if (!authService.hasAudioConsent(userId)) {
            throw ApiException.forbidden("Audio processing consent is required before speaking sessions");
        }
        String topic = null;
        if (taskId != null) {
            LearningTask task = taskRepository.findByIdAndUserId(taskId, userId)
                    .orElseThrow(() -> ApiException.notFound("Task"));
            // 任务内容中保存了当天情景主题，优先作为口语话题
            try {
                com.fasterxml.jackson.databind.JsonNode content =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(task.getContentJson());
                String fromContent = content.path("topic").asText("").trim();
                topic = fromContent.isBlank() ? task.getTitle() : fromContent;
            } catch (Exception ignored) {
                topic = task.getTitle();
            }
        }
        // 无任务也能开始情景口语：使用当天情景主题（不依赖每日任务生成）
        if (topic == null || topic.isBlank()) {
            topic = dailyThemeService.topicFor(java.time.LocalDate.now());
        }
        // 额度：并发会话与每日时长（Redis 降级放行）；时长在结束时按实际用时计费
        enforceQuota(userId);

        // 实时语音能力：按 enable+provider 配置路由（无密钥回退 MOCK）
        SpeechResult rt = speechGateway.invoke(AiCapability.REALTIME, SpeechRequest.realtime(null, null));
        String providerCode = rt.vendorCode() == null ? "MOCK" : rt.vendorCode();
        String modelCode = rt.modelCode() == null ? "mock-realtime" : rt.modelCode();
        String transport = "HTTP";
        SpeakingSession session = new SpeakingSession(userId, taskId, providerCode, modelCode, transport);
        session.setTopic(topic);
        sessionRepository.save(session);

        // AI 开场白：创建会话时同步生成，随响应直接返回（REST 模式，无 WebSocket）
        Map<String, Object> opening = messageService.generateOpening(session.getId());

        eventLogger.log(userId, "SPEAKING_SESSION_CREATED",
                Map.of("sessionId", session.getId().toString(), "mode", mode == null ? "FREE_CONVERSATION" : mode,
                        "topic", topic == null ? "" : topic));

        return Map.of(
                "sessionId", session.getId().toString(),
                "status", session.getStatus(),
                "provider", providerCode,
                "model", modelCode,
                "transport", transport,
                "topic", topic == null ? "" : topic,
                "opening", opening,
                "maxDurationSeconds", Math.min(maxDurationSeconds, 1800),
                "quotaRemainingMinutes", quotaRemainingMinutes(userId));
    }

    /**
     * 短时凭证：短 TTL、单会话作用域；mock 环境签发本地会话 token。
     */
    @Transactional
    public Map<String, Object> credential(UUID sessionId, String clientPlatform, String requestedTransport) {
        SpeakingSession session = owned(sessionId);
        if (SpeakingSession.ENDED.equals(session.getStatus())) {
            throw ApiException.invalid("Session already ended", "sessionId", sessionId.toString());
        }
        String ephemeralToken = "spk-" + UUID.randomUUID() + "-" + sessionId;
        return Map.of(
                "provider", session.getProviderCode(),
                "ephemeralToken", ephemeralToken,
                "expiresAt", Instant.now().plusSeconds(600).toString(),
                "rtcConfig", Map.of("mode", "provider-managed", "transport", session.getTransport()));
    }

    /**
     * 可继续/可回看的会话：
     * - 有未结束（CREATED/CONNECTED）的会话 → resumed=true，进入续接；
     * - 否则额度未用完 → resumed=false（前端创建新会话继续练习）；
     * - 额度已用完 → reviewSessionId 返回最近一次会话（任意状态），前端进入只读回看。
     * taskId 可选：限定指定任务的会话。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> resumable(UUID taskId) {
        UUID userId = CurrentUser.require().id();
        long remaining = quotaRemainingMinutes(userId);
        Optional<SpeakingSession> active = taskId == null
                ? sessionRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(
                        userId, List.of(SpeakingSession.CREATED, SpeakingSession.CONNECTED))
                : sessionRepository.findFirstByUserIdAndTaskIdAndStatusInOrderByCreatedAtDesc(
                        userId, taskId, List.of(SpeakingSession.CREATED, SpeakingSession.CONNECTED));
        if (active.isPresent()) {
            SpeakingSession s = active.get();
            return Map.<String, Object>of(
                    "resumed", true,
                    "sessionId", s.getId().toString(),
                    "status", s.getStatus(),
                    "topic", s.getTopic() == null ? "" : s.getTopic(),
                    "quotaRemainingMinutes", remaining);
        }
        if (remaining > 0) {
            return Map.of("resumed", false, "quotaRemainingMinutes", remaining);
        }
        // 额度用尽：仍可进入回看最近的对话
        Optional<SpeakingSession> latest = taskId == null
                ? sessionRepository.findFirstByUserIdOrderByCreatedAtDesc(userId)
                : sessionRepository.findFirstByUserIdAndTaskIdOrderByCreatedAtDesc(userId, taskId);
        return latest.map(s -> Map.<String, Object>of(
                        "resumed", false,
                        "reviewSessionId", s.getId().toString(),
                        "topic", s.getTopic() == null ? "" : s.getTopic(),
                        "quotaRemainingMinutes", remaining))
                .orElse(Map.of("resumed", false, "quotaRemainingMinutes", remaining));
    }

    /**
     * 重新开始：结束当前会话（计费、不生成课后反馈），
     * 用同样的任务/话题立即创建一个新会话并异步生成开场白。
     */
    @Transactional
    public Map<String, Object> restart(UUID sessionId) {
        SpeakingSession old = owned(sessionId);
        UUID userId = old.getUserId();
        if (!SpeakingSession.ENDED.equals(old.getStatus())) {
            old.end();
            sessionRepository.save(old);
            if (old.getDurationMs() != null) {
                chargeQuota(userId, old.getDurationMs());
            }
            eventLogger.log(userId, "SPEAKING_SESSION_RESTARTED",
                    Map.of("sessionId", sessionId.toString(),
                            "durationMs", old.getDurationMs() == null ? 0 : old.getDurationMs()));
        }

        enforceQuota(userId);
        SpeechResult rt = speechGateway.invoke(AiCapability.REALTIME, SpeechRequest.realtime(null, null));
        SpeakingSession session = new SpeakingSession(userId, old.getTaskId(),
                rt.vendorCode() == null ? "MOCK" : rt.vendorCode(),
                rt.modelCode() == null ? "mock-realtime" : rt.modelCode(),
                "HTTP");
        session.setTopic(old.getTopic());
        sessionRepository.save(session);

        messageService.generateOpening(session.getId());
        eventLogger.log(userId, "SPEAKING_SESSION_CREATED",
                Map.of("sessionId", session.getId().toString(), "mode", "RESTART",
                        "topic", old.getTopic() == null ? "" : old.getTopic()));

        return Map.of(
                "sessionId", session.getId().toString(),
                "status", session.getStatus(),
                "topic", old.getTopic() == null ? "" : old.getTopic(),
                "transport", "HTTP",
                "quotaRemainingMinutes", quotaRemainingMinutes(userId));
    }

    /** 会话历史轮次（续接对话时前端恢复消息列表；语音轮次附预签名音频 URL 供点击回放）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> turns(UUID sessionId) {
        owned(sessionId);
        return turnRepository.findBySessionIdOrderBySequenceNoAsc(sessionId).stream()
                .map(t -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("turnId", t.getId().toString());
                    m.put("sequenceNo", t.getSequenceNo());
                    m.put("role", t.getRole());
                    m.put("transcript", t.getTranscript() == null ? "" : t.getTranscript());
                    m.put("metadata", read(t.getMetadataJson()));
                    String key = t.getAudioObjectKey();
                    if (key != null && !key.isBlank()) {
                        try {
                            m.put("audioUrl", key.startsWith("private/")
                                    ? storage.presignDownload(key)
                                    : storage.presignContentUrl(key));
                        } catch (Exception ignored) {
                            // 预签名失败不影响历史列表
                        }
                    }
                    return m;
                })
                .toList();
    }

    /**
     * 结束会话：202 + feedbackJobId，异步纠错与总结。
     * 每日额度按本次实际用时计费，返回剩余额度（未用完可继续新的情景对话）。
     */
    @Transactional
    public Map<String, Object> finish(UUID sessionId, String endedBy, String clientLastEventId) {
        SpeakingSession session = owned(sessionId);
        if (SpeakingSession.ENDED.equals(session.getStatus())) {
            return Map.of("status", "ENDED", "feedbackJobId",
                    session.getSummaryJobId() == null ? "" : session.getSummaryJobId().toString(),
                    "quotaRemainingMinutes", quotaRemainingMinutes(session.getUserId()));
        }
        session.end();
        sessionRepository.save(session);
        chargeQuota(session.getUserId(), session.getDurationMs());
        eventLogger.log(session.getUserId(), "SPEAKING_SESSION_ENDED",
                Map.of("sessionId", sessionId.toString(), "endedBy", endedBy == null ? "USER" : endedBy,
                        "durationMs", session.getDurationMs() == null ? 0 : session.getDurationMs()));

        JobRecord job = jobService.create("SPEAKING_FEEDBACK", null);
        UUID sessionIdCopy = session.getId();
        jobService.runAsync(job, () -> {
            Map<String, Object> feedback = correctionService.generateFeedback(sessionIdCopy);
            return Map.entry("feedback:" + sessionIdCopy, json(feedback));
        });
        session.setSummaryJobId(job.getId());
        sessionRepository.save(session);
        return Map.of("status", "PROCESSING", "feedbackJobId", job.getId().toString(),
                "quotaRemainingMinutes", quotaRemainingMinutes(session.getUserId()));
    }

    /**
     * 课后反馈：job 未完成返回 202 语义（code=PROCESSING），完成返回纠错报告。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> feedback(UUID sessionId) {
        SpeakingSession session = owned(sessionId);
        if (session.getSummaryJobId() == null) {
            return Map.of("status", "NOT_STARTED");
        }
        JobRecord job = jobService.get(session.getSummaryJobId());
        if (!"SUCCEEDED".equals(job.getStatus())) {
            return Map.of("status", job.getStatus(), "retryAfterSeconds", 2);
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>(mapper.convertValue(read(job.getResultJson()),
                new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, Object>>() {
                }));
        result.put("status", "READY");
        result.put("sessionId", sessionId.toString());
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history() {
        UUID userId = CurrentUser.require().id();
        return sessionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(s -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("sessionId", s.getId().toString());
                    m.put("status", s.getStatus());
                    m.put("provider", s.getProviderCode());
                    m.put("model", s.getModelCode());
                    m.put("transport", s.getTransport());
                    m.put("startedAt", s.getStartedAt() == null ? "" : s.getStartedAt().toString());
                    m.put("endedAt", s.getEndedAt() == null ? "" : s.getEndedAt().toString());
                    m.put("durationMs", s.getDurationMs() == null ? 0 : s.getDurationMs());
                    m.put("createdAt", s.getCreatedAt().toString());
                    return m;
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public SpeakingSession owned(UUID sessionId) {
        return sessionRepository.findByIdAndUserId(sessionId, CurrentUser.require().id())
                .orElseThrow(() -> ApiException.notFound("Speaking session"));
    }

    private static final int DAILY_LIMIT_MINUTES = 120;

    /** CONNECTED 会话超过该时长无消息活动视为遗留（如客户端直接关闭标签页）。 */
    private static final Duration STALE_CONNECTED_AFTER = Duration.ofMinutes(10);

    private void enforceQuota(UUID userId) {
        try {
            expireStaleConnections(userId);
            long concurrent = sessionRepository.countByUserIdAndStatus(userId, SpeakingSession.CONNECTED);
            if (concurrent >= 2) {
                throw new ApiException(com.speakpath.common.api.ErrorCode.QUOTA_EXCEEDED,
                        "Too many concurrent speaking sessions");
            }
            if (usedMinutes(userId) >= DAILY_LIMIT_MINUTES) {
                throw new ApiException(com.speakpath.common.api.ErrorCode.QUOTA_EXCEEDED,
                        "Daily speaking quota exceeded (120 minutes)");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            // fail-open：Redis 不可用时不阻塞核心学习流程
        }
    }

    /** 清理超时未活动的 CONNECTED 会话（客户端遗留），直接结束、不扣今日额度、不生成反馈。 */
    private void expireStaleConnections(UUID userId) {
        Instant cutoff = Instant.now().minus(STALE_CONNECTED_AFTER);
        for (SpeakingSession stale : sessionRepository
                .findByUserIdAndStatusAndStartedAtBefore(userId, SpeakingSession.CONNECTED, cutoff)) {
            stale.end();
            sessionRepository.save(stale);
            log.info("Expired stale speaking session {}", stale.getId());
        }
    }

    /** 会话结束时按实际用时计费（向上取整，至少 1 分钟）。 */
    private void chargeQuota(UUID userId, Long durationMs) {
        try {
            long minutes = Math.max(1, (long) Math.ceil((durationMs == null ? 60000 : durationMs) / 60000.0));
            String dayKey = "quota:speaking:" + userId + ":" + java.time.LocalDate.now();
            redis.opsForValue().increment(dayKey, minutes);
            redis.expire(dayKey, Duration.ofHours(24));
        } catch (Exception ignored) {
            // fail-open
        }
    }

    private long usedMinutes(UUID userId) {
        try {
            String dayKey = "quota:speaking:" + userId + ":" + java.time.LocalDate.now();
            String current = redis.opsForValue().get(dayKey);
            return current == null ? 0 : Long.parseLong(current);
        } catch (Exception e) {
            return 0;
        }
    }

    private long quotaRemainingMinutes(UUID userId) {
        return Math.max(0, DAILY_LIMIT_MINUTES - usedMinutes(userId));
    }

    /** 今日口语额度状态：剩余未用完时用户可继续新的情景对话。 */
    public Map<String, Object> quotaStatus() {
        UUID userId = CurrentUser.require().id();
        long used = usedMinutes(userId);
        return Map.of("dailyLimitMinutes", DAILY_LIMIT_MINUTES,
                "usedMinutes", used,
                "remainingMinutes", Math.max(0, DAILY_LIMIT_MINUTES - used));
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
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
