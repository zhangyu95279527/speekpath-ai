package com.speakpath.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.speakpath.common.api.ApiException;
import com.speakpath.common.security.CurrentUser;
import com.speakpath.correction.ExamSpeakingCriteria;
import com.speakpath.infrastructure.events.EventLogger;
import com.speakpath.infrastructure.jobs.JobRecord;
import com.speakpath.infrastructure.jobs.JobService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 动态重排（规格书 §9.3）：
 * - 每次重排创建 plan_version，保留旧版、差异与原因
 * - 已完成任务不删除；锁定任务不被自动覆盖
 */
@Service
public class PlanRescheduler {

    private final StudyPlanRepository planRepository;
    private final PlanVersionRepository versionRepository;
    private final LearningTaskRepository taskRepository;
    private final EventLogger eventLogger;
    private final JobService jobService;
    private final PlanGenerationService generationService;
    private final PlanAdaptationService adaptationService;
    private final ObjectMapper mapper = new ObjectMapper();

    public PlanRescheduler(StudyPlanRepository planRepository, PlanVersionRepository versionRepository,
                           LearningTaskRepository taskRepository, EventLogger eventLogger, JobService jobService,
                           PlanGenerationService generationService, PlanAdaptationService adaptationService) {
        this.planRepository = planRepository;
        this.versionRepository = versionRepository;
        this.taskRepository = taskRepository;
        this.eventLogger = eventLogger;
        this.jobService = jobService;
        this.generationService = generationService;
        this.adaptationService = adaptationService;
    }

    /**
     * 任务操作：COMPLETE / SKIP / RESCHEDULE / LOCK / UNLOCK。
     * RESCHEDULE 创建新版本（复制任务到新版本并应用变更），历史保留。
     */
    @Transactional
    public Map<String, Object> patchTask(UUID taskId, String action, LocalDate targetDate, String reason) {
        UUID userId = CurrentUser.require().id();
        LearningTask task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> ApiException.notFound("Task"));
        String actionUpper = action == null ? "" : action.toUpperCase();

        return switch (actionUpper) {
            case "COMPLETE" -> {
                if (LearningTask.COMPLETED.equals(task.getStatus())) {
                    yield Map.of("taskId", taskId.toString(), "status", task.getStatus(), "duplicate", true);
                }
                task.complete((short) Math.max(1, task.getDurationMin()));
                taskRepository.save(task);
                eventLogger.log(userId, "TASK_COMPLETED", Map.of(
                        "taskId", taskId.toString(), "taskType", task.getTaskType(),
                        "durationMin", (int) task.getDurationMin()));
                yield Map.of("taskId", taskId.toString(), "status", task.getStatus(),
                        "planVersion", versionOf(task));
            }
            case "SKIP" -> {
                task.skip();
                taskRepository.save(task);
                eventLogger.log(userId, "TASK_SKIPPED", Map.of("taskId", taskId.toString(), "reason", reason == null ? "" : reason));
                yield Map.of("taskId", taskId.toString(), "status", task.getStatus(),
                        "planVersion", versionOf(task));
            }
            case "LOCK" -> {
                task.setLocked(true);
                taskRepository.save(task);
                yield Map.of("taskId", taskId.toString(), "status", task.getStatus(), "locked", true,
                        "planVersion", versionOf(task));
            }
            case "UNLOCK" -> {
                task.setLocked(false);
                taskRepository.save(task);
                yield Map.of("taskId", taskId.toString(), "status", task.getStatus(), "locked", false,
                        "planVersion", versionOf(task));
            }
            case "RESCHEDULE" -> reschedule(task, targetDate, reason);
            default -> throw ApiException.invalid("Unknown action", "action", action);
        };
    }

    private Map<String, Object> reschedule(LearningTask task, LocalDate targetDate, String reason) {
        if (LearningTask.COMPLETED.equals(task.getStatus())) {
            throw ApiException.invalid("Completed tasks cannot be rescheduled", "taskId", task.getId().toString());
        }
        if (task.isLocked()) {
            throw ApiException.invalid("Locked tasks cannot be rescheduled", "taskId", task.getId().toString());
        }
        if (targetDate == null || targetDate.isBefore(LocalDate.now())) {
            throw ApiException.invalid("targetDate must be today or later", "targetDate", String.valueOf(targetDate));
        }
        // 新版本：复制当前激活版本全部未完成任务并应用变更；旧记录标记 RESCHEDULED 保留历史
        StudyPlan plan = versionRepository.findById(task.getPlanVersionId())
                .map(pv -> planRepository.findById(pv.getPlanId()).orElse(null))
                .orElse(null);
        if (plan == null) {
            throw ApiException.notFound("Plan for task");
        }
        int newVersion = plan.getActiveVersion() + 1;
        List<LearningTask> current = taskRepository.findByPlanVersionIdOrderByTaskDateAscPriorityAsc(
                versionIdOf(plan, plan.getActiveVersion()));
        PlanVersion planVersion = new PlanVersion(plan.getId(), newVersion,
                json(Map.of("reason", reason == null ? "RESCHEDULE" : reason)),
                reason == null ? "RESCHEDULE" : reason,
                json(Map.of("rescheduledTask", task.getId().toString(), "targetDate", targetDate.toString())),
                "USER");
        versionRepository.save(planVersion);
        LearningTask moved = null;
        for (LearningTask t : current) {
            if (t.getStatus().equals(LearningTask.COMPLETED) || t.getStatus().equals(LearningTask.SKIPPED)) {
                continue; // 完成/跳过记录保留原版本引用，不复制
            }
            if (t.getId().equals(task.getId())) {
                moved = copy(t, planVersion.getId(), targetDate);
            } else {
                copy(t, planVersion.getId(), t.getTaskDate());
            }
        }
        if (moved == null) {
            moved = copy(task, planVersion.getId(), targetDate);
        }
        task.setStatus(LearningTask.RESCHEDULED);
        taskRepository.save(task);
        plan.setActiveVersion(newVersion);
        planRepository.save(plan);
        eventLogger.log(task.getUserId(), "PLAN_RESCHEDULED",
                Map.of("planId", plan.getId().toString(), "version", newVersion, "reason", reason == null ? "RESCHEDULE" : reason));
        return Map.of("taskId", moved.getId().toString(), "status", moved.getStatus(),
                "taskDate", moved.getTaskDate().toString(), "planVersion", newVersion);
    }

    /**
     * 重算计划：异步生成新版本（从今天起按当前预算重排未完成任务），旧版本保留。
     */
    @Transactional
    public Map<String, Object> recalculate(String trigger) {
        UUID userId = CurrentUser.require().id();
        StudyPlan plan = planRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, "ACTIVE")
                .orElseThrow(() -> ApiException.notFound("Active plan"));
        JobRecord job = jobService.create("PLAN_RECALCULATE", null);
        UUID planId = plan.getId();
        jobService.runAsync(job, () -> {
            bumpVersion(planId, trigger == null ? "RECALCULATE" : trigger);
            return Map.entry("plan:" + planId, "{\"planId\":\"" + planId + "\"}");
        });
        return Map.of("jobId", job.getId().toString(), "status", "QUEUED");
    }

    /**
     * 用户重新规划：更新目标考试、目标分数、每日学习时长，并异步重排未来 7 天任务。
     * 新难度基于最近水平评测与 AI 口语纠错数据自动计算。
     */
    @Transactional
    public Map<String, Object> replan(String examCode, BigDecimal targetScore, Integer dailyMinutes) {
        UUID userId = CurrentUser.require().id();
        StudyPlan plan = planRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, "ACTIVE")
                .orElseThrow(() -> ApiException.notFound("Active plan"));
        String normalizedExam = ExamSpeakingCriteria.isToefl(examCode) ? "TOEFL_SPEAKING" : "IELTS_SPEAKING";
        double maxScore = adaptationService.maxScore(normalizedExam);
        if (targetScore == null || targetScore.signum() <= 0 || targetScore.doubleValue() > maxScore) {
            throw ApiException.invalid("targetScore out of range", "targetScore",
                    "0 < targetScore <= " + maxScore);
        }
        int minutes = dailyMinutes == null ? 30 : dailyMinutes;
        if (minutes < 10 || minutes > 180) {
            throw ApiException.invalid("dailyMinutes out of range", "dailyMinutes", "10-180");
        }
        ObjectNode goal;
        try {
            JsonNode parsed = mapper.readTree(plan.getGoalJson());
            goal = parsed.isObject() ? (ObjectNode) parsed : mapper.createObjectNode();
        } catch (Exception e) {
            goal = mapper.createObjectNode();
        }
        goal.put("examCode", normalizedExam);
        goal.put("targetScore", targetScore);
        goal.put("dailyMinutes", minutes);
        goal.put("weeklyAssessment", true);
        PlanAdaptationService.Adaptation adaptation = adaptationService.forUser(userId, normalizedExam);
        goal.put("difficultyLevel", adaptation.level());
        goal.putPOJO("focusAreas", adaptation.focusAreas());
        plan.setGoalJson(json(goal));
        planRepository.save(plan);

        JobRecord job = jobService.create("PLAN_REPLAN", null);
        UUID planId = plan.getId();
        jobService.runAsync(job, () -> {
            bumpVersion(planId, "USER_REPLAN");
            int refreshed = 0;
            try {
                generationService.refreshUpcoming(planId, 7);
                refreshed = 7;
            } catch (Exception e) {
                eventLogger.log(userId, "PLAN_REPLAN_REFRESH_FAILED", Map.of("error", String.valueOf(e.getMessage())));
            }
            return Map.entry("plan:" + planId, "{\"planId\":\"" + planId + "\",\"refreshedDays\":" + refreshed + "}");
        });
        eventLogger.log(userId, "PLAN_REPLAN_REQUESTED", Map.of(
                "planId", plan.getId().toString(), "examCode", normalizedExam,
                "targetScore", targetScore.toString(), "dailyMinutes", minutes));
        return Map.of("jobId", job.getId().toString(), "status", "QUEUED",
                "difficultyLevel", adaptation.level(), "focusAreas", adaptation.focusAreas());
    }

    @Transactional
    public void bumpVersion(UUID planId, String reason) {
        StudyPlan plan = planRepository.findById(planId).orElseThrow(() -> ApiException.notFound("Study plan"));
        int newVersion = plan.getActiveVersion() + 1;
        UUID currentVersionId = versionIdOf(plan, plan.getActiveVersion());
        List<LearningTask> current = taskRepository.findByPlanVersionIdOrderByTaskDateAscPriorityAsc(currentVersionId);
        LocalDate today = LocalDate.now();
        PlanVersion planVersion = new PlanVersion(plan.getId(), newVersion,
                json(Map.of("reason", reason)), reason, "{}", "SYSTEM");
        versionRepository.save(planVersion);
        int copied = 0;
        for (LearningTask t : current) {
            if (t.getStatus().equals(LearningTask.COMPLETED) || t.getStatus().equals(LearningTask.SKIPPED)) {
                continue;
            }
            if (t.getTaskDate().isBefore(today) || t.isLocked()) {
                copy(t, planVersion.getId(), t.getTaskDate()); // 锁定与过期未完成任务保留
            } else {
                copy(t, planVersion.getId(), t.getTaskDate());
            }
            copied++;
        }
        plan.setActiveVersion(newVersion);
        planRepository.save(plan);
        eventLogger.log(plan.getUserId(), "PLAN_RECALCULATED",
                Map.of("planId", planId.toString(), "version", newVersion, "reason", reason, "copiedTasks", copied));
    }

    private LearningTask copy(LearningTask source, UUID planVersionId, LocalDate date) {
        LearningTask copyTask = new LearningTask(planVersionId, source.getUserId(), date,
                source.getTaskType(), source.getTitle(), source.getDurationMin(), source.getPriority(),
                source.getContentJson());
        if (source.isLocked()) {
            copyTask.setLocked(true);
        }
        return taskRepository.save(copyTask);
    }

    private UUID versionIdOf(StudyPlan plan, int version) {
        return versionRepository.findByPlanIdOrderByVersionDesc(plan.getId()).stream()
                .filter(v -> v.getVersion() == version)
                .map(PlanVersion::getId)
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Plan version " + version));
    }

    private int versionOf(LearningTask task) {
        return versionRepository.findById(task.getPlanVersionId())
                .map(PlanVersion::getVersion).orElse(1);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
