package com.speakpath.progress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speakpath.assessment.AssessmentReport;
import com.speakpath.assessment.AssessmentReportRepository;
import com.speakpath.assessment.AssessmentSession;
import com.speakpath.assessment.AssessmentSessionRepository;
import com.speakpath.common.security.CurrentUser;
import com.speakpath.correction.CorrectionItemRepository;
import com.speakpath.correction.ExamSpeakingCriteria;
import com.speakpath.plan.StudyPlanRepository;
import com.speakpath.speaking.SpeakingSession;
import com.speakpath.speaking.SpeakingSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/**
 * 学习进度概览：完成率、练习时长、能力趋势、错误分布。
 */
@Service
public class ProgressService {

    private final AssessmentSessionRepository sessionRepository;
    private final AssessmentReportRepository reportRepository;
    private final CorrectionItemRepository correctionRepository;
    private final SpeakingSessionRepository speakingSessionRepository;
    private final StudyPlanRepository planRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProgressService(AssessmentSessionRepository sessionRepository,
                           AssessmentReportRepository reportRepository,
                           CorrectionItemRepository correctionRepository,
                           SpeakingSessionRepository speakingSessionRepository,
                           StudyPlanRepository planRepository) {
        this.sessionRepository = sessionRepository;
        this.reportRepository = reportRepository;
        this.correctionRepository = correctionRepository;
        this.speakingSessionRepository = speakingSessionRepository;
        this.planRepository = planRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        UUID userId = CurrentUser.require().id();
        // 每日任务已取消“完成/跳过”操作：以实际口语练习（会话绑定的任务）衡量任务参与
        List<SpeakingSession> speakingSessions = speakingSessionRepository
                .findByUserIdOrderByCreatedAtDesc(userId);
        Set<UUID> practicedTaskIds = new LinkedHashSet<>();
        for (SpeakingSession session : speakingSessions) {
            if (session.getTaskId() != null) {
                practicedTaskIds.add(session.getTaskId());
            }
        }
        int totalMinutes = speakingSessions.stream().mapToInt(this::sessionMinutes).sum();

        // 能力趋势：最近报告维度平均分
        List<Map<String, Object>> trend = new ArrayList<>();
        for (AssessmentSession session : sessionRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (!"COMPLETED".equals(session.getStatus())) {
                continue;
            }
            reportRepository.findBySessionId(session.getId()).ifPresent(report -> {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("sessionId", session.getId().toString());
                point.put("createdAt", report.getCreatedAt() == null ? "" : report.getCreatedAt().toString());
                point.put("overallLow", report.getOverallLow());
                point.put("overallHigh", report.getOverallHigh());
                point.put("confidence", report.getConfidence());
                JsonNode reportJson = read(report.getReportJson());
                Map<String, Double> dims = new LinkedHashMap<>();
                reportJson.path("dimensions").forEach(d -> dims.put(d.path("code").asText(), d.path("score").asDouble()));
                point.put("dimensions", dims);
                trend.add(point);
            });
            if (trend.size() >= 6) {
                break;
            }
        }
        Collections.reverse(trend);

        // 错误分布：按用户当前考试（IELTS/TOEFL 等）的口语评分维度归类
        String examCode = examCodeOf(userId);
        Map<String, Long> rawDistribution = new HashMap<>();
        for (Object[] row : correctionRepository.countByCategoryForUser(userId)) {
            String code = ExamSpeakingCriteria.normalize(examCode, String.valueOf(row[0]));
            rawDistribution.merge(code, ((Number) row[1]).longValue(), Long::sum);
        }
        Map<String, Long> errorDistribution = new LinkedHashMap<>();
        Map<String, String> errorDistributionLabels = new LinkedHashMap<>();
        for (ExamSpeakingCriteria.Criterion criterion : ExamSpeakingCriteria.of(examCode)) {
            Long count = rawDistribution.remove(criterion.code());
            if (count != null) {
                errorDistribution.put(criterion.code(), count);
                errorDistributionLabels.put(criterion.code(), criterion.label());
            }
        }
        rawDistribution.forEach((code, count) -> {
            errorDistribution.put(code, count);
            errorDistributionLabels.put(code, ExamSpeakingCriteria.label(examCode, code));
        });

        // 近 7 日练习任务数（按当日口语会话绑定的任务统计）
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> dailyCompletion = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            dailyCompletion.add(Map.of("date", date.toString(),
                    "completed", practicedTaskCountOn(speakingSessions, date)));
        }

        return Map.of(
                "completedTasks", practicedTaskIds.size(),
                "totalPracticeMinutes", totalMinutes,
                "completedReports", trend.size(),
                "dimensionTrend", trend,
                "examCode", examCode,
                "examName", ExamSpeakingCriteria.examName(examCode),
                "errorDistribution", errorDistribution,
                "errorDistributionLabels", errorDistributionLabels,
                "last7DaysCompletion", dailyCompletion);
    }

    /** 用户当前计划对应的考试；无计划时按 IELTS 口语标准归类。 */
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

    private int practicedTaskCountOn(List<SpeakingSession> sessions, LocalDate date) {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
        Set<UUID> taskIds = new LinkedHashSet<>();
        boolean freeSpeaking = false;
        for (SpeakingSession session : sessions) {
            if (session.getCreatedAt() == null
                    || session.getCreatedAt().isBefore(from) || !session.getCreatedAt().isBefore(to)) {
                continue;
            }
            if (session.getTaskId() != null) {
                taskIds.add(session.getTaskId());
            } else {
                freeSpeaking = true;
            }
        }
        return taskIds.size() + (freeSpeaking ? 1 : 0);
    }

    private int sessionMinutes(SpeakingSession session) {
        if (session.getDurationMs() != null && session.getDurationMs() > 0) {
            return (int) Math.max(0, Math.round(session.getDurationMs() / 60000.0));
        }
        if (session.getStartedAt() != null && session.getEndedAt() != null) {
            return (int) Math.max(0, Math.round(
                    Duration.between(session.getStartedAt(), session.getEndedAt()).toMillis() / 60000.0));
        }
        return 0;
    }

    /**
     * 成绩趋势：每次水平评测的综合分随时间的变化，以及当前分与目标分的差距。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> scoreTrend() {
        UUID userId = CurrentUser.require().id();
        String examCode = "IELTS_SPEAKING";
        java.math.BigDecimal target = null;
        var plan = planRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, "ACTIVE").orElse(null);
        if (plan != null) {
            JsonNode goal = read(plan.getGoalJson());
            examCode = goal.path("examCode").asText("IELTS_SPEAKING");
            if (goal.hasNonNull("targetScore")) {
                target = goal.get("targetScore").decimalValue();
            }
        }
        double maxScore = ExamSpeakingCriteria.isToefl(examCode) ? 30.0 : 9.0;
        List<Map<String, Object>> points = new ArrayList<>();
        for (AssessmentSession session : sessionRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (!"COMPLETED".equals(session.getStatus())) {
                continue;
            }
            AssessmentReport report = reportRepository.findBySessionId(session.getId()).orElse(null);
            if (report == null || report.getOverallLow() == null || report.getOverallHigh() == null) {
                continue;
            }
            Instant at = report.getCreatedAt() == null ? session.getCreatedAt() : report.getCreatedAt();
            LocalDate day = at == null ? LocalDate.now() : at.atZone(ZoneId.systemDefault()).toLocalDate();
            double score = report.getOverallLow().add(report.getOverallHigh())
                    .divide(java.math.BigDecimal.valueOf(2), 1, java.math.RoundingMode.HALF_UP).doubleValue();
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", day.toString());
            point.put("score", score);
            point.put("sessionId", session.getId().toString());
            points.add(point);
        }
        java.util.Collections.reverse(points);
        Double current = points.isEmpty() ? null : ((Number) points.get(points.size() - 1).get("score")).doubleValue();
        Double gap = current == null || target == null ? null
                : java.math.BigDecimal.valueOf(target.doubleValue() - current)
                        .setScale(1, java.math.RoundingMode.HALF_UP).doubleValue();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("examCode", examCode);
        result.put("examName", ExamSpeakingCriteria.examName(examCode));
        result.put("maxScore", maxScore);
        result.put("targetScore", target);
        result.put("currentScore", current);
        result.put("gap", gap);
        result.put("points", points);
        return result;
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }
}
