package com.speakpath.progress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.speakpath.ai.gateway.AiCapability;
import com.speakpath.ai.gateway.AiGateway;
import com.speakpath.common.security.CurrentUser;
import com.speakpath.plan.LearningTask;
import com.speakpath.plan.LearningTaskRepository;
import com.speakpath.plan.PlanVersionRepository;
import com.speakpath.plan.StudyPlan;
import com.speakpath.plan.StudyPlanRepository;
import com.speakpath.speaking.SpeakingSession;
import com.speakpath.speaking.SpeakingSessionRepository;
import com.speakpath.speaking.SpeakingTurn;
import com.speakpath.speaking.SpeakingTurnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/**
 * 每日学习评价：每天 0 点后自动统计前一天的任务完成情况、口语时长、
 * 对话轮次与发音得分，并生成中文点评与建议。
 */
@Service
public class DailyLearningReportService {

    private static final Logger log = LoggerFactory.getLogger(DailyLearningReportService.class);

    private final DailyLearningReportRepository reportRepository;
    private final StudyPlanRepository planRepository;
    private final PlanVersionRepository versionRepository;
    private final LearningTaskRepository taskRepository;
    private final SpeakingSessionRepository sessionRepository;
    private final SpeakingTurnRepository turnRepository;
    private final AiGateway aiGateway;
    private final ObjectMapper mapper = new ObjectMapper();

    public DailyLearningReportService(DailyLearningReportRepository reportRepository,
                                      StudyPlanRepository planRepository,
                                      PlanVersionRepository versionRepository,
                                      LearningTaskRepository taskRepository,
                                      SpeakingSessionRepository sessionRepository,
                                      SpeakingTurnRepository turnRepository,
                                      AiGateway aiGateway) {
        this.reportRepository = reportRepository;
        this.planRepository = planRepository;
        this.versionRepository = versionRepository;
        this.taskRepository = taskRepository;
        this.sessionRepository = sessionRepository;
        this.turnRepository = turnRepository;
        this.aiGateway = aiGateway;
    }

    /** 每天 0 点（Asia/Shanghai）评价前一天的学习结果。 */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Shanghai")
    public void evaluateYesterday() {
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        Set<UUID> userIds = new HashSet<>();
        for (StudyPlan plan : planRepository.findAll()) {
            if ("ACTIVE".equals(plan.getStatus())) {
                userIds.add(plan.getUserId());
            }
        }
        for (UUID userId : userIds) {
            try {
                evaluate(userId, date);
            } catch (Exception e) {
                log.warn("Daily learning evaluation failed for user={} date={}: {}", userId, date, e.getMessage());
            }
        }
        log.info("Daily learning evaluation finished for {} user(s) on {}", userIds.size(), date);
    }

    /** 评价指定用户某一天的学习结果（幂等 upsert）。 */
    @Transactional
    public Map<String, Object> evaluate(UUID userId, LocalDate date) {
        List<LearningTask> tasks = activeVersionTasks(userId, date);
        List<SpeakingSession> sessions = sessionsOn(userId, date);

        int speakingMinutes = 0;
        int turns = 0;
        List<Integer> pronunciationScores = new ArrayList<>();
        for (SpeakingSession session : sessions) {
            speakingMinutes += sessionMinutes(session);
            for (SpeakingTurn turn : turnRepository.findBySessionIdOrderBySequenceNoAsc(session.getId())) {
                if ("USER".equals(turn.getRole())) {
                    turns++;
                    Integer overall = pronunciationOverall(turn.getMetadataJson());
                    if (overall != null && overall > 0) {
                        pronunciationScores.add(overall);
                    }
                }
            }
        }
        Integer pronunciationAvg = pronunciationScores.isEmpty() ? null
                : (int) Math.round(pronunciationScores.stream().mapToInt(Integer::intValue).average().orElse(0));

        int tasksTotal = tasks.size();
        // 每日任务已取消“完成/跳过”操作，改为以实际口语练习判定任务参与情况：
        // 会话绑定了任务则计该任务；从口语入口进入（未绑定任务）视为练习当天 SCENARIO 任务。
        Set<UUID> practicedTaskIds = new HashSet<>();
        boolean freeSpeaking = false;
        for (SpeakingSession session : sessions) {
            if (session.getTaskId() != null) {
                practicedTaskIds.add(session.getTaskId());
            } else {
                freeSpeaking = true;
            }
        }
        boolean free = freeSpeaking;
        int tasksDone = (int) tasks.stream()
                .filter(t -> practicedTaskIds.contains(t.getId())
                        || (free && "SCENARIO".equals(t.getTaskType())))
                .count();
        int score = score(tasksTotal, tasksDone, speakingMinutes, turns, pronunciationAvg);

        DailyLearningReport report = reportRepository.findByUserIdAndReportDate(userId, date)
                .orElseGet(() -> new DailyLearningReport(userId, date));
        report.setTasksTotal(tasksTotal);
        report.setTasksDone(tasksDone);
        report.setSpeakingSessions(sessions.size());
        report.setSpeakingMinutes(speakingMinutes);
        report.setTurnsCount(turns);
        report.setPronunciationAvg(pronunciationAvg);
        report.setScore(score);

        JsonNode evaluation = aiEvaluate(date, tasksTotal, tasksDone, sessions.size(), speakingMinutes,
                turns, pronunciationAvg, score);
        report.setSummary(evaluation.path("summary").asText(""));
        report.setSuggestions(suggestionsJson(evaluation.path("suggestions"), tasksTotal, speakingMinutes,
                turns, pronunciationAvg));
        reportRepository.save(report);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportDate", date.toString());
        result.put("tasksTotal", tasksTotal);
        result.put("tasksDone", tasksDone);
        result.put("speakingSessions", sessions.size());
        result.put("speakingMinutes", speakingMinutes);
        result.put("turnsCount", turns);
        result.put("pronunciationAvg", pronunciationAvg);
        result.put("score", score);
        result.put("summary", report.getSummary());
        result.put("suggestions", read(report.getSuggestions()));
        return result;
    }

    /** 最近 7 天（含今天）每日评价（学习进度页展示）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> latestReports() {
        UUID userId = CurrentUser.require().id();
        LocalDate from = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(6);
        return reportRepository.findByUserIdAndReportDateGreaterThanEqualOrderByReportDateDesc(userId, from)
                .stream()
                .map(this::toDto).toList();
    }

    /** 仅统计当前激活计划版本的任务，避免重排后旧版本任务叠加。 */
    private List<LearningTask> activeVersionTasks(UUID userId, LocalDate date) {
        StudyPlan plan = planRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, "ACTIVE")
                .orElse(null);
        if (plan == null) {
            return List.of();
        }
        return versionRepository.findByPlanIdOrderByVersionDesc(plan.getId()).stream()
                .filter(v -> v.getVersion() == plan.getActiveVersion())
                .findFirst()
                .map(v -> taskRepository.findByUserIdAndTaskDateAndPlanVersionIdOrderByPriorityAsc(
                        userId, date, v.getId()))
                .orElse(List.of());
    }

    private Map<String, Object> toDto(DailyLearningReport report) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("reportDate", report.getReportDate().toString());
        dto.put("tasksTotal", report.getTasksTotal());
        dto.put("tasksDone", report.getTasksDone());
        dto.put("speakingSessions", report.getSpeakingSessions());
        dto.put("speakingMinutes", report.getSpeakingMinutes());
        dto.put("turnsCount", report.getTurnsCount());
        dto.put("pronunciationAvg", report.getPronunciationAvg());
        dto.put("score", report.getScore());
        dto.put("summary", report.getSummary());
        dto.put("suggestions", read(report.getSuggestions()));
        return dto;
    }

    private List<SpeakingSession> sessionsOn(UUID userId, LocalDate date) {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
        return sessionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(s -> s.getCreatedAt() != null
                        && !s.getCreatedAt().isBefore(from) && s.getCreatedAt().isBefore(to))
                .toList();
    }

    private int sessionMinutes(SpeakingSession session) {
        if (session.getDurationMs() != null && session.getDurationMs() > 0) {
            return (int) Math.max(0, Math.round(session.getDurationMs() / 60000.0));
        }
        if (session.getStartedAt() != null && session.getEndedAt() != null) {
            return (int) Math.max(0, Math.round(Duration.between(session.getStartedAt(), session.getEndedAt()).toMillis() / 60000.0));
        }
        return 0;
    }

    private Integer pronunciationOverall(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            JsonNode overall = mapper.readTree(metadataJson).path("pronunciation").path("overall");
            return overall.isNumber() ? overall.asInt() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private int score(int tasksTotal, int tasksDone, int speakingMinutes, int turns, Integer pronunciationAvg) {
        int score = 50;
        score += Math.min(15, speakingMinutes);
        score += Math.min(15, turns / 2);
        score += tasksTotal == 0 ? 0 : (int) Math.round(10.0 * tasksDone / tasksTotal);
        if (pronunciationAvg != null) {
            score += Math.max(0, Math.min(10, (pronunciationAvg - 60) / 4));
        }
        return Math.max(0, Math.min(100, score));
    }

    private JsonNode aiEvaluate(LocalDate date, int tasksTotal, int tasksDone, int sessions, int minutes,
                                int turns, Integer pronunciationAvg, int score) {
        String systemPrompt = """
                You are an encouraging English speaking coach for Chinese learners.
                Given one day's learning statistics, write a short evaluation in Chinese.
                Note: tasksDone means how many daily tasks the user actually practiced via speaking
                (there is no manual complete/skip action), so never blame the user for "not completing" tasks.
                When referring to tasksDone, use the wording "练习了 N 个任务" instead of "完成任务".
                Return a single JSON object only:
                {"summary":"2-3 sentences in Chinese, specific and encouraging","suggestions":["3 short actionable suggestions in Chinese"]}
                """;
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("date", date.toString());
        input.put("tasksTotal", tasksTotal);
        input.put("tasksDone", tasksDone);
        input.put("speakingSessions", sessions);
        input.put("speakingMinutes", minutes);
        input.put("turnsCount", turns);
        input.put("pronunciationAvg", pronunciationAvg);
        input.put("score", score);
        try {
            JsonNode out = aiGateway.invoke(new AiGateway.Invocation(AiCapability.PLANNING,
                            systemPrompt, input, null, Duration.ofSeconds(30), "daily-report-v1"),
                    new AiGateway.ModelInvocationContext(null, null, null, "STANDARD")).output();
            if (out != null && out.isObject() && !out.path("summary").asText("").isBlank()) {
                return out;
            }
        } catch (Exception e) {
            log.warn("LLM daily evaluation unavailable for {}: {}", date, e.getMessage());
        }
        ObjectNode fallback = mapper.createObjectNode();
        fallback.put("summary", fallbackSummary(tasksTotal, tasksDone, minutes, turns, pronunciationAvg));
        ArrayNode list = fallback.putArray("suggestions");
        if (minutes < 10) {
            list.add("明天先完成 10 分钟情景口语，保持开口习惯。");
        } else {
            list.add("保持今天的学习节奏，尝试延长 5 分钟对话。");
        }
        if (pronunciationAvg != null && pronunciationAvg < 75) {
            list.add("跟读练习时放慢语速，注意单词重音和句子语调。");
        } else {
            list.add("尝试用今天学到的表达复述一段自己的经历。");
        }
        list.add("睡前回顾今日纠错内容，挑 2 条造句巩固。");
        return fallback;
    }

    private String fallbackSummary(int tasksTotal, int tasksDone, int minutes, int turns, Integer pronunciationAvg) {
        if (minutes == 0 && turns == 0) {
            return "昨天没有进行口语练习，今天可以从一段 5 分钟的情景对话开始，先找回开口的感觉。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("昨天你完成了 ").append(minutes).append(" 分钟口语练习，共 ").append(turns).append(" 轮对话");
        if (tasksTotal > 0) {
            sb.append("，练习任务 ").append(tasksDone).append("/").append(tasksTotal);
        }
        sb.append("。");
        if (pronunciationAvg != null) {
            sb.append("发音平均 ").append(pronunciationAvg).append(" 分，");
            sb.append(pronunciationAvg >= 80 ? "整体清晰自然，继续保持。" : "建议多跟读、注意重音与连读。");
        } else if (minutes > 0) {
            sb.append("继续多开口，发音会越来越自然。");
        }
        return sb.toString();
    }

    private String suggestionsJson(JsonNode suggestions, int tasksTotal, int minutes, int turns,
                                   Integer pronunciationAvg) {
        if (suggestions != null && suggestions.isArray() && !suggestions.isEmpty()) {
            return suggestions.toString();
        }
        ArrayNode fallback = mapper.createArrayNode();
        fallback.add(minutes < 10 ? "明天先完成 10 分钟情景口语。" : "保持节奏，尝试延长对话时间。");
        fallback.add("复习今天的纠错表达，并各造一个句子。");
        if (pronunciationAvg != null && pronunciationAvg < 75) {
            fallback.add("跟读时关注重音与语调，录下自己的声音对比。");
        } else {
            fallback.add("尝试用新学的表达讲述当天发生的一件事。");
        }
        return fallback.toString();
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }
}
