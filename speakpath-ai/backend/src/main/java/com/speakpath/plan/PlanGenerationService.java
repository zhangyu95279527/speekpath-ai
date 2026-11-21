package com.speakpath.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.speakpath.ai.gateway.AiCapability;
import com.speakpath.ai.gateway.AiGateway;
import com.speakpath.assessment.AssessmentReport;
import com.speakpath.assessment.AssessmentReportRepository;
import com.speakpath.assessment.AssessmentSession;
import com.speakpath.assessment.AssessmentSessionRepository;
import com.speakpath.common.api.ApiException;
import com.speakpath.common.security.CurrentUser;
import com.speakpath.infrastructure.events.EventLogger;
import com.speakpath.infrastructure.jobs.JobRecord;
import com.speakpath.infrastructure.jobs.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * 计划生成（规格书 §9）：
 * 计算总预算 → 每日结构分配（热身 10-15% / 专项 25-30% / 情景 30-40% / 纠错 15-20% / 小测 5-10%）
 * → 按每日分钟装箱 → 跳过休息日 → 约束校验 → 生成版本与理由。
 */
@Service
public class PlanGenerationService {

    private static final Logger log = LoggerFactory.getLogger(PlanGenerationService.class);

    private final StudyPlanRepository planRepository;
    private final PlanVersionRepository versionRepository;
    private final LearningTaskRepository taskRepository;
    private final ContentItemRepository contentRepository;
    private final AssessmentReportRepository reportRepository;
    private final AssessmentSessionRepository sessionRepository;
    private final JobService jobService;
    private final AiGateway aiGateway;
    private final EventLogger eventLogger;
    private final TaskAudioService taskAudioService;
    private final ContentMaterialService contentMaterialService;
    private final DailyThemeService dailyThemeService;
    private final PlanAdaptationService adaptationService;
    private final com.speakpath.infrastructure.storage.StorageService storage;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 主题化每日任务内容版本：版本升级时当天任务会原地重建一次。 */
    private static final String THEMED_CONTENT_VERSION = "daily-theme-v3";

    public PlanGenerationService(StudyPlanRepository planRepository, PlanVersionRepository versionRepository,
                                 LearningTaskRepository taskRepository, ContentItemRepository contentRepository,
                                 AssessmentReportRepository reportRepository, AssessmentSessionRepository sessionRepository,
                                 JobService jobService, AiGateway aiGateway, EventLogger eventLogger,
                                 TaskAudioService taskAudioService, ContentMaterialService contentMaterialService,
                                 DailyThemeService dailyThemeService,
                                 PlanAdaptationService adaptationService,
                                 com.speakpath.infrastructure.storage.StorageService storage) {
        this.planRepository = planRepository;
        this.versionRepository = versionRepository;
        this.taskRepository = taskRepository;
        this.contentRepository = contentRepository;
        this.reportRepository = reportRepository;
        this.sessionRepository = sessionRepository;
        this.jobService = jobService;
        this.aiGateway = aiGateway;
        this.eventLogger = eventLogger;
        this.taskAudioService = taskAudioService;
        this.contentMaterialService = contentMaterialService;
        this.dailyThemeService = dailyThemeService;
        this.adaptationService = adaptationService;
        this.storage = storage;
    }

    /**
     * 创建计划并异步生成（202 + jobId，幂等）。
     */
    @Transactional
    public Map<String, Object> create(UUID reportId, LocalDate startDate, LocalDate endDate,
                                      int dailyMinutes, List<Integer> restDays, String timezone,
                                      Map<String, Object> lockedPreferences) {
        UUID userId = CurrentUser.require().id();
        if (dailyMinutes < 5 || dailyMinutes > 300) {
            throw ApiException.invalid("dailyMinutes must be between 5 and 300", "dailyMinutes", String.valueOf(dailyMinutes));
        }
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            throw ApiException.invalid("Invalid date range", "startDate", "endDate must be after startDate");
        }
        if (endDate.isAfter(startDate.plusDays(365))) {
            throw ApiException.invalid("Plan range too long", "endDate", "max 365 days");
        }
        AssessmentReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> ApiException.notFound("Report"));
        AssessmentSession session = sessionRepository.findById(report.getSessionId())
                .orElseThrow(() -> ApiException.notFound("Assessment session"));
        if (!session.getUserId().equals(userId)) {
            throw ApiException.forbidden("Report does not belong to current user");
        }

        // 已有 ACTIVE 计划则归档（保留历史）
        planRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, "ACTIVE")
                .ifPresent(old -> {
                    old.setStatus("ARCHIVED");
                    planRepository.save(old);
                });

        Map<String, Object> goal = Map.of(
                "examCode", session.getExamCode(),
                "reportId", reportId.toString(),
                "dailyMinutes", dailyMinutes,
                "restDays", restDays == null ? List.of() : restDays,
                "timezone", timezone == null ? "Asia/Shanghai" : timezone,
                "startDate", startDate.toString(),
                "endDate", endDate.toString());
        StudyPlan plan = new StudyPlan(userId, reportId, startDate, endDate, json(goal));
        planRepository.save(plan);

        JobRecord job = jobService.create("PLAN_GENERATION", null);
        UUID planId = plan.getId();
        jobService.runAsync(job, () -> {
            generate(planId);
            return Map.entry("plan:" + planId, "{\"planId\":\"" + planId + "\"}");
        });
        return Map.of("jobId", job.getId().toString(), "status", "QUEUED", "planId", planId.toString());
    }

    /**
     * 生成任务（约束求解：每日合计不超过预算；休息日跳过）。
     */
    @Transactional
    public void generate(UUID planId) {
        StudyPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> ApiException.notFound("Study plan"));
        JsonNode goal = read(plan.getGoalJson());
        int dailyMinutes = goal.path("dailyMinutes").asInt(30);
        List<Integer> restDays = new ArrayList<>();
        goal.path("restDays").forEach(d -> restDays.add(d.asInt()));

        List<ContentItem> allContents = contentRepository.findByStatusOrderByItemTypeAscDifficultyAsc("PUBLISHED");
        // 优先使用材料已就绪的内容项，避免生成“有任务无内容”的空壳任务
        List<ContentItem> contents = allContents.stream()
                .filter(c -> ContentMaterialService.READY.equals(c.getMaterialStatus()))
                .toList();
        if (contents.isEmpty()) {
            contents = allContents;
        }
        if (contents.isEmpty()) {
            throw ApiException.invalid("No published content available", "content", "seed content_items first");
        }

        // AI 规划理由（mock/真实模型），失败不阻塞约束求解
        String planningNote = "约束求解生成：每日预算 " + dailyMinutes + " 分钟。";
        try {
            JsonNode ai = aiGateway.invoke(new AiGateway.Invocation(AiCapability.PLANNING,
                            "You are a study planner. Given the goal and daily minute budget, summarize a sensible study approach. "
                                    + "Output a single JSON object with: \"summary\" (string describing the plan rationale).",
                            Map.of("goal", goal, "dailyMinutes", dailyMinutes), null,
                            Duration.ofSeconds(20), "planning-v1"),
                    new AiGateway.ModelInvocationContext(plan.getUserId(), null, null, "STANDARD")).output();
            if (ai.hasNonNull("summary")) {
                planningNote = ai.path("summary").asText();
            }
        } catch (Exception e) {
            log.warn("PLANNING model unavailable, using constraint solver only: {}", e.getMessage());
        }

        List<LearningTask> tasks = new ArrayList<>();
        String examCode = goal.path("examCode").asText("IELTS_SPEAKING");
        BigDecimal targetScore = goal.hasNonNull("targetScore") ? goal.get("targetScore").decimalValue() : null;
        int level = adaptationService.levelOf(adaptationService.latestScore(plan.getUserId()), examCode);
        LocalDate date = plan.getStartDate();
        while (!date.isAfter(plan.getEndDate())) {
            if (!restDays.contains(date.getDayOfWeek().getValue() % 7)) {
                tasks.addAll(buildDay(date, dailyMinutes, contents, plan.getUserId(), planId));
                long index = dayIndex(plan, date);
                if (index > 0 && index % 7 == 0) {
                    String theme = dailyThemeService.topicFor(date);
                    tasks.add(assessmentTask(null, plan.getUserId(), date,
                            theme == null ? "阶段评测" : theme, examCode, targetScore, level));
                }
            }
            date = date.plusDays(1);
        }

        int version = 1;
        PlanVersion planVersion = new PlanVersion(planId, version,
                json(Map.of("dailyMinutes", dailyMinutes, "restDays", restDays,
                        "generatedNote", planningNote, "taskCount", tasks.size())),
                "INITIAL_GENERATION", "{}", "SYSTEM");
        planVersion = versionRepository.save(planVersion);
        for (LearningTask task : tasks) {
            taskRepository.save(new LearningTask(planVersion.getId(), task.getUserId(), task.getTaskDate(),
                    task.getTaskType(), task.getTitle(), task.getDurationMin(), task.getPriority(),
                    task.getContentJson()));
        }
        plan.setActiveVersion(version);
        planRepository.save(plan);
        // 任务创建时预生成示范音频（内容项级缓存，幂等；异步不阻塞计划生成）
        for (String contentItemId : tasks.stream()
                .map(t -> read(t.getContentJson()).path("contentItemId").asText(""))
                .filter(s -> !s.isBlank()).distinct().toList()) {
            JobRecord audioJob = jobService.create("CONTENT_AUDIO_PREP", null);
            UUID itemId = UUID.fromString(contentItemId);
            jobService.runAsync(audioJob, () -> {
                taskAudioService.ensureContentAudio(itemId);
                return Map.entry("content-audio:" + itemId, "{\"contentItemId\":\"" + itemId + "\"}");
            });
        }
        eventLogger.log(plan.getUserId(), "PLAN_GENERATED",
                Map.of("planId", planId.toString(), "taskCount", tasks.size()));
    }

    private List<LearningTask> buildDay(LocalDate date, int dailyMinutes, List<ContentItem> contents,
                                        UUID userId, UUID planId) {
        Map<String, Double> ratios = new LinkedHashMap<>();
        ratios.put("WARMUP", 0.12);
        ratios.put("SKILL", 0.28);
        ratios.put("SCENARIO", 0.35);
        ratios.put("CORRECTION", 0.18);
        ratios.put("QUIZ", 0.07);

        List<LearningTask> day = new ArrayList<>();
        int budget = dailyMinutes;
        // 按比例从内容库选取；不足时按可用内容补齐
        for (Map.Entry<String, Double> entry : ratios.entrySet()) {
            int alloc = (int) Math.floor(dailyMinutes * entry.getValue());
            if (alloc <= 0) {
                continue;
            }
            List<ContentItem> pool = contents.stream().filter(c -> c.getItemType().equals(entry.getKey())).toList();
            if (pool.isEmpty()) {
                continue;
            }
            int used = 0;
            int idx = Math.floorMod(date.toEpochDay(), pool.size());
            while (used < alloc && budget > 0) {
                ContentItem item = pool.get(idx % pool.size());
                short duration = (short) Math.min(item.getDurationMin(), budget);
                if (duration <= 0) {
                    break;
                }
                short priority = (short) switch (entry.getKey()) {
                    case "WARMUP" -> 1;
                    case "SKILL" -> 2;
                    case "SCENARIO" -> 3;
                    case "CORRECTION" -> 4;
                    default -> 5;
                };
                day.add(new LearningTask(null, userId, date, entry.getKey(), item.getTitle(), duration,
                        priority, json(Map.of("contentItemId", item.getId().toString(),
                        "instructions", read(item.getContentJson()).path("instructions").asText("")))));
                used += duration;
                budget -= duration;
                idx++;
                if (used >= alloc) {
                    break;
                }
            }
        }
        // 剩余预算：追加情景自由对话
        List<ContentItem> scenario = contents.stream().filter(c -> c.getItemType().equals("SCENARIO")).toList();
        int idx = Math.floorMod(date.toEpochDay() + 1, Math.max(1, scenario.size()));
        while (budget >= 5 && !scenario.isEmpty()) {
            ContentItem item = scenario.get(idx % scenario.size());
            short duration = (short) Math.min(10, budget);
            day.add(new LearningTask(null, userId, date, "SCENARIO", item.getTitle(), duration, (short) 3,
                    json(Map.of("contentItemId", item.getId().toString(),
                            "instructions", read(item.getContentJson()).path("instructions").asText("")))));
            budget -= duration;
            idx++;
        }
        return day;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> activePlan() {
        UUID userId = CurrentUser.require().id();
        StudyPlan plan = planRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, "ACTIVE")
                .orElse(null);
        if (plan == null) {
            return Map.of("hasPlan", false);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hasPlan", true);
        result.put("planId", plan.getId().toString());
        result.put("startDate", plan.getStartDate().toString());
        result.put("endDate", plan.getEndDate().toString());
        result.put("activeVersion", plan.getActiveVersion());
        JsonNode goal = read(plan.getGoalJson());
        result.put("dailyMinutes", goal.path("dailyMinutes").asInt(30));
        result.put("restDays", goal.path("restDays"));
        result.put("examCode", goal.path("examCode").asText(""));
        if (goal.hasNonNull("targetScore")) {
            result.put("targetScore", goal.get("targetScore").decimalValue());
        }
        result.put("weeklyAssessment", goal.path("weeklyAssessment").asBoolean(true));
        if (goal.hasNonNull("difficultyLevel")) {
            result.put("difficultyLevel", goal.get("difficultyLevel").asInt());
        }
        result.put("focusAreas", goal.path("focusAreas"));
        return result;
    }

    @Transactional
    public List<Map<String, Object>> tasks(LocalDate date, String timezone) {
        UUID userId = CurrentUser.require().id();
        LocalDate effective = date == null ? LocalDate.now(zoneOf(timezone)) : date;
        return ensureTasksForDate(userId, effective).stream()
                .map(this::taskDto).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> upcomingTasks(int days) {
        UUID userId = CurrentUser.require().id();
        LocalDate from = LocalDate.now(ZoneId.systemDefault());
        StudyPlan plan = planRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, "ACTIVE")
                .orElse(null);
        if (plan == null) {
            return List.of();
        }
        // 仅返回当前激活版本的任务，避免历史版本叠加
        return versionRepository.findByPlanIdOrderByVersionDesc(plan.getId()).stream()
                .filter(v -> v.getVersion() == plan.getActiveVersion())
                .findFirst()
                .map(v -> taskRepository.findByPlanVersionIdOrderByTaskDateAscPriorityAsc(v.getId()).stream()
                        .filter(t -> !t.getTaskDate().isBefore(from))
                        .limit(Math.max(30, days * 5L))
                        .map(this::taskDto).toList())
                .orElse(List.of());
    }

    /**
     * 每日任务保障：当天任务必须与当天情景口语主题一致。
     * 旧任务（无主题标记）在当天首次访问时重建；内容材料仍在后台生成时返回空列表，
     * 由客户端轮询就绪接口。
     */
    @Transactional
    public synchronized List<LearningTask> ensureTasksForDate(UUID userId, LocalDate date) {
        StudyPlan plan = planRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, "ACTIVE")
                .orElse(null);
        if (plan == null) {
            return List.of();
        }
        // 只取当前激活版本的任务，避免“重新规划”后旧版本任务叠加导致时长/任务数逐日增长
        UUID activeVersionId = versionRepository.findByPlanIdOrderByVersionDesc(plan.getId()).stream()
                .filter(v -> v.getVersion() == plan.getActiveVersion())
                .findFirst().map(PlanVersion::getId).orElse(null);
        if (activeVersionId == null) {
            return List.of();
        }
        List<LearningTask> existing = taskRepository
                .findByUserIdAndTaskDateAndPlanVersionIdOrderByPriorityAsc(userId, date, activeVersionId);
        String theme = dailyThemeService.topicFor(date);
        boolean rebuild = false;
        if (!existing.isEmpty()) {
            boolean versionOutdated = existing.stream().anyMatch(t -> !THEMED_CONTENT_VERSION
                    .equals(read(t.getContentJson()).path("contentVersion").asText("")));
            if (theme == null || (!versionOutdated && isThemeAligned(existing, theme)) || !date.equals(LocalDate.now())) {
                return existing;
            }
            // 当天任务主题或内容版本不一致：稍后原位更新内容（保留任务 ID，口语会话可能引用）
            rebuild = true;
        }
        JsonNode goal = read(plan.getGoalJson());
        if (isRestDay(goal, date)) {
            return List.of();
        }
        // 内容材料未就绪时先不生成，避免生成空任务
        if (contentMaterialService.hasPendingMaterials()) {
            return List.of();
        }
        List<ContentItem> contents = contentRepository.findByStatusOrderByItemTypeAscDifficultyAsc("PUBLISHED");
        if (contents.isEmpty()) {
            return List.of();
        }
        int dailyMinutes = goal.path("dailyMinutes").asInt(30);
        String examCode = goal.path("examCode").asText("IELTS_SPEAKING");
        BigDecimal targetScore = goal.hasNonNull("targetScore") ? goal.get("targetScore").decimalValue() : null;
        boolean weeklyAssessment = dayIndex(plan, date) > 0 && dayIndex(plan, date) % 7 == 0;
        List<LearningTask> generated = generateThemedDay(userId, date, dailyMinutes, contents,
                dailyThemeService.scenarioFor(date).orElse(null), activeVersionId, examCode, targetScore,
                weeklyAssessment);
        if (generated.isEmpty()) {
            return List.of();
        }
        if (rebuild) {
            for (LearningTask fresh : generated) {
                existing.stream()
                        .filter(old -> old.getTaskType().equals(fresh.getTaskType()))
                        .findFirst()
                        .ifPresent(old -> {
                            old.updateContent(fresh.getTitle(), fresh.getDurationMin(), fresh.getPriority(),
                                    fresh.getContentJson());
                            taskRepository.save(old);
                        });
            }
            log.info("Daily tasks re-themed for user={} date={} theme={}", userId, date, theme);
            return taskRepository.findByUserIdAndTaskDateAndPlanVersionIdOrderByPriorityAsc(
                    userId, date, activeVersionId);
        }
        List<LearningTask> saved = generated.stream().map(taskRepository::save).toList();
        log.info("Daily themed tasks generated for user={} date={} theme={} count={}",
                userId, date, theme, saved.size());
        return saved;
    }

    private boolean isThemeAligned(List<LearningTask> tasks, String theme) {
        return tasks.stream().allMatch(task -> theme.equals(read(task.getContentJson()).path("theme").asText("")));
    }

    /** 生成与当天情景主题一致的一组任务（一次 LLM 调用产出全部练习材料）。 */
    private List<LearningTask> generateThemedDay(UUID userId, LocalDate date, int dailyMinutes,
                                                 List<ContentItem> contents, ContentItem scenario,
                                                 UUID planVersionId, String examCode, BigDecimal targetScore,
                                                 boolean weeklyAssessment) {
        String theme = scenario == null ? dailyThemeService.topicFor(date) : dailyThemeService.topicOf(scenario);
        if (theme == null || theme.isBlank()) {
            theme = "Daily English";
        }
        PlanAdaptationService.Adaptation adaptation = adaptationService.forUser(userId, examCode);
        int warmup = Math.max(3, Math.round(dailyMinutes * 0.12f));
        int skill = Math.max(4, Math.round(dailyMinutes * 0.25f));
        int correction = Math.max(4, Math.round(dailyMinutes * 0.18f));
        int quiz = Math.max(3, Math.round(dailyMinutes * 0.10f));
        int scenarioMin = Math.max(5, dailyMinutes - warmup - skill - correction - quiz);

        JsonNode day = aiGenerateThemedDay(theme, adaptation);
        List<LearningTask> tasks = new ArrayList<>();
        tasks.add(themedTask(planVersionId, userId, date, 1, "WARMUP", theme + " · 跟读热身", warmup,
                "shadowing", "sentences", day == null ? null : day.path("warmup"), contents, theme));
        tasks.add(themedTask(planVersionId, userId, date, 2, "SKILL", theme + " · 表达练习", skill,
                "practice", "items", day == null ? null : day.path("skill"), contents, theme));
        tasks.add(themedTask(planVersionId, userId, date, 3, "CORRECTION", theme + " · 纠错练习", correction,
                "collocation", "items", day == null ? null : day.path("correction"), contents, theme));
        tasks.add(themedTask(planVersionId, userId, date, 4, "QUIZ", theme + " · 小结测验", quiz,
                "summary", "items", day == null ? null : day.path("quiz"), contents, theme));
        tasks.add(scenarioTask(planVersionId, userId, date, 5, theme, scenarioMin, scenario, theme));
        if (weeklyAssessment) {
            tasks.add(assessmentTask(planVersionId, userId, date, theme, examCode, targetScore, adaptation.level()));
        }
        return tasks;
    }

    /** 每 7 天额外插入一次水平评测任务（不占用常规练习预算，10 分钟）。 */
    private LearningTask assessmentTask(UUID planVersionId, UUID userId, LocalDate date, String theme,
                                        String examCode, BigDecimal targetScore, int level) {
        ObjectNode content = mapper.createObjectNode();
        content.put("theme", theme);
        content.put("exerciseType", "assessment");
        content.put("contentVersion", THEMED_CONTENT_VERSION);
        content.put("examCode", examCode);
        if (targetScore != null) {
            content.put("targetScore", targetScore);
        }
        content.put("difficultyLevel", level);
        content.put("instructions",
                "Complete a 10-15 minute speaking level assessment. 完成一次口语水平评测，系统将据此调整下一阶段的学习难度。");
        return new LearningTask(planVersionId, userId, date, "ASSESSMENT", theme + " · 水平评测",
                (short) 10, (short) 5, content.toString());
    }

    private long dayIndex(StudyPlan plan, LocalDate date) {
        return java.time.temporal.ChronoUnit.DAYS.between(plan.getStartDate(), date);
    }

    /**
     * 重新规划后刷新未来若干天的任务：保持任务 ID 原位更新（口语会话可能引用），
     * 并补建每 7 天的水平评测任务。
     */
    @Transactional
    public void refreshUpcoming(UUID planId, int days) {
        StudyPlan plan = planRepository.findById(planId).orElseThrow(() -> ApiException.notFound("Study plan"));
        JsonNode goal = read(plan.getGoalJson());
        int dailyMinutes = goal.path("dailyMinutes").asInt(30);
        String examCode = goal.path("examCode").asText("IELTS_SPEAKING");
        BigDecimal targetScore = goal.hasNonNull("targetScore") ? goal.get("targetScore").decimalValue() : null;
        UUID versionId = versionRepository.findByPlanIdOrderByVersionDesc(planId).stream()
                .filter(v -> v.getVersion() == plan.getActiveVersion())
                .findFirst().map(PlanVersion::getId).orElse(null);
        if (versionId == null) {
            return;
        }
        List<ContentItem> contents = contentRepository.findByStatusOrderByItemTypeAscDifficultyAsc("PUBLISHED");
        if (contents.isEmpty()) {
            return;
        }
        LocalDate today = LocalDate.now();
        for (int i = 0; i < days; i++) {
            LocalDate date = today.plusDays(i);
            if (isRestDay(goal, date) || date.isAfter(plan.getEndDate())) {
                continue;
            }
            List<LearningTask> existing = taskRepository
                    .findByUserIdAndTaskDateAndPlanVersionIdOrderByPriorityAsc(plan.getUserId(), date, versionId);
            boolean weekly = dayIndex(plan, date) > 0 && dayIndex(plan, date) % 7 == 0;
            List<LearningTask> generated = generateThemedDay(plan.getUserId(), date, dailyMinutes, contents,
                    dailyThemeService.scenarioFor(date).orElse(null), versionId, examCode, targetScore, weekly);
            if (generated.isEmpty()) {
                continue;
            }
            if (existing.isEmpty()) {
                generated.forEach(taskRepository::save);
                continue;
            }
            for (LearningTask fresh : generated) {
                existing.stream()
                        .filter(old -> old.getTaskType().equals(fresh.getTaskType()))
                        .findFirst()
                        .ifPresentOrElse(old -> {
                            old.updateContent(fresh.getTitle(), fresh.getDurationMin(), fresh.getPriority(),
                                    fresh.getContentJson());
                            taskRepository.save(old);
                        }, () -> taskRepository.save(fresh));
            }
        }
        log.info("Upcoming tasks refreshed after replan: plan={} days={}", planId, days);
    }

    private LearningTask themedTask(UUID planVersionId, UUID userId, LocalDate date, int priority,
                                    String taskType, String title, int minutes, String exerciseType,
                                    String arrayField, JsonNode generated, List<ContentItem> contents, String theme) {
        ObjectNode content = mapper.createObjectNode();
        content.put("theme", theme);
        content.put("exerciseType", exerciseType);
        content.put("contentVersion", THEMED_CONTENT_VERSION);

        JsonNode section = generated != null && generated.isObject() && !generated.isMissingNode()
                ? generated : null;
        JsonNode array = section == null ? null : section.path(arrayField);
        if (array == null || !array.isArray() || array.isEmpty()) {
            // LLM 不可用时回退到内容库中同类型材料，仍保留当天主题
            ContentItem fallback = pickContent(contents, taskType, date);
            if (fallback != null) {
                JsonNode fb = read(fallback.getContentJson());
                array = fb.path(arrayField);
                content.put("contentItemId", fallback.getId().toString());
                String instructions = fb.path("instructions").asText("");
                content.put("instructions", instructions.isBlank()
                        ? "Practice the expressions for the theme: " + theme : instructions);
            }
        } else {
            String instructions = section.path("instructions").asText("");
            content.put("instructions", instructions.isBlank()
                    ? "Practice the expressions for the theme: " + theme : instructions);
        }
        if (array != null && array.isArray()) {
            content.set(arrayField, array);
        }
        return new LearningTask(planVersionId, userId, date, taskType, title, (short) minutes, (short) priority,
                content.toString());
    }

    private LearningTask scenarioTask(UUID planVersionId, UUID userId, LocalDate date, int priority,
                                      String theme, int minutes, ContentItem scenario, String fallbackTheme) {
        ObjectNode content = mapper.createObjectNode();
        content.put("theme", fallbackTheme);
        content.put("exerciseType", "scenario");
        content.put("contentVersion", THEMED_CONTENT_VERSION);
        content.put("topic", theme);
        if (scenario == null) {
            content.put("instructions", "Role-play the scenario with the AI tutor.");
        } else {
            content.put("contentItemId", scenario.getId().toString());
            JsonNode sc = read(scenario.getContentJson());
            content.put("topic", sc.path("topic").asText(theme));
            content.put("instructions", sc.path("instructions").asText("Role-play the scenario with the AI tutor."));
            content.set("openers", sc.path("openers"));
            content.set("targetPhrases", sc.path("targetPhrases"));
        }
        return new LearningTask(planVersionId, userId, date, "SCENARIO", theme, (short) minutes, (short) priority,
                content.toString());
    }

    private ContentItem pickContent(List<ContentItem> contents, String taskType, LocalDate date) {
        List<ContentItem> candidates = contents.stream()
                .filter(c -> taskType.equals(c.getItemType()))
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        int index = (int) Math.floorMod(date.toEpochDay(), candidates.size());
        return candidates.get(index);
    }

    /** 一次调用生成当天主题下的跟读/表达/纠错/测验材料。 */
    private JsonNode aiGenerateThemedDay(String theme, PlanAdaptationService.Adaptation adaptation) {
        String levelBand = switch (adaptation.level()) {
            case 1 -> "A1-A2 (very simple, short sentences)";
            case 2 -> "A2-B1 (simple daily sentences)";
            case 3 -> "B1 (clear everyday sentences with some linking words)";
            case 4 -> "B1-B2 (varied structures and richer vocabulary)";
            default -> "B2-C1 (complex sentences, abstract vocabulary, advanced grammar)";
        };
        String focus = adaptation.focusAreas().isEmpty()
                ? "general accuracy and fluency" : String.join(", ", adaptation.focusAreas());
        String systemPrompt = """
                You design one day's English speaking-practice set around a single theme for Chinese learners.
                Return a single JSON object only, no markdown, no extra text:
                {
                  "warmup": {"instructions":"English instruction","sentences":[{"text":"English sentence","note":"中文提示"}]},
                  "skill": {"instructions":"English instruction","items":[{"text":"English sentence","note":"中文要点"}]},
                  "correction": {"instructions":"English instruction","items":[{"text":"Correct English sentence","note":"common mistake -> correction, 中文说明"}]},
                  "quiz": {"instructions":"English instruction","items":[{"text":"English prompt","note":"中文提示"}]}
                }
                Requirements:
                - Every sentence and phrase must be natural English strictly related to the theme.
                - Provide 4 to 5 entries in every array.
                - The learner's target level is %s. Entry 1 should be slightly easier than this level and the final entry
                  slightly harder, so difficulty increases sentence by sentence.
                - Within EACH array, order the entries from easiest to hardest: difficulty must increase sentence by sentence
                  in vocabulary (high-frequency/concrete -> less common/abstract), grammar (basic patterns -> varied structures),
                  tense (simple present -> past/perfect -> future or hypothetical) and sentence structure
                  (simple -> compound -> complex with subordinate clauses).
                - Emphasize the learner's weak areas in skill and correction items: %s. Add focused practice on them.
                - warmup.sentences are for shadowing: keep each one speakable and let the length grow gradually by about 2-4 words per entry.
                - Apply the same easy-to-hard progression to skill, correction and quiz.
                """.formatted(levelBand, focus);
        try {
            JsonNode out = aiGateway.invoke(new AiGateway.Invocation(AiCapability.PLANNING,
                            systemPrompt,
                            Map.of("theme", theme, "difficultyLevel", adaptation.level(),
                                    "focusAreas", adaptation.focusAreas()),
                            null, Duration.ofSeconds(45), "daily-theme-v3"),
                    new AiGateway.ModelInvocationContext(null, null, null, "STANDARD")).output();
            return out != null && out.isObject() ? out : null;
        } catch (Exception e) {
            log.warn("Themed daily content generation failed for theme={}: {}", theme, e.getMessage());
            return null;
        }
    }


    /** 任务内容就绪状态：供客户端轮询，内容生成完成前返回 ready=false。 */
    @Transactional
    public Map<String, Object> taskReadiness(LocalDate date, String timezone) {
        UUID userId = CurrentUser.require().id();
        LocalDate effective = date == null ? LocalDate.now(zoneOf(timezone)) : date;
        List<LearningTask> tasks = ensureTasksForDate(userId, effective);

        int total = tasks.size();
        int pending = 0;
        for (LearningTask task : tasks) {
            if (!ContentMaterialService.hasMaterial(read(task.getContentJson()))) {
                pending++;
            }
        }

        boolean materialsPending = contentMaterialService.hasPendingMaterials()
                || contentMaterialService.isGenerating();
        // 无任务时：内容仍在生成 -> 未就绪；否则视为就绪（无计划/休息日）
        boolean ready = total == 0 ? !materialsPending : pending == 0;
        boolean generating = contentMaterialService.isGenerating() || (total > 0 && pending > 0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", effective.toString());
        result.put("ready", ready);
        result.put("generating", generating);
        result.put("totalCount", total);
        result.put("doneCount", total - pending);
        result.put("pendingCount", pending);
        result.put("materialTotal", contentMaterialService.getTotalCount());
        result.put("materialDone", contentMaterialService.getDoneCount());
        result.put("progress", total == 0 ? (materialsPending ? 0 : 100)
                : (int) Math.round((total - pending) * 100.0 / total));
        return result;
    }

    private boolean isRestDay(JsonNode goal, LocalDate date) {
        int day = date.getDayOfWeek().getValue() % 7;
        JsonNode restDays = goal.path("restDays");
        if (restDays.isArray()) {
            for (JsonNode node : restDays) {
                if (node.asInt() == day) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 任务详情：任务字段 + 内容库完整练习材料（contentItemId 指向的内容项），并附示范音频预签名 URL。 */
    @Transactional(readOnly = true)
    public Map<String, Object> taskDetail(UUID taskId) {
        UUID userId = CurrentUser.require().id();
        LearningTask task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> ApiException.notFound("Task"));
        Map<String, Object> dto = taskDto(task);
        String itemId = read(task.getContentJson()).path("contentItemId").asText("");
        if (!itemId.isBlank()) {
            try {
                contentRepository.findById(UUID.fromString(itemId)).ifPresent(item -> {
                    ObjectNode merged = (ObjectNode) read(item.getContentJson());
                    merged.put("contentItemId", itemId);
                    attachAudioUrls(merged);
                    dto.put("content", mapper.convertValue(merged, Map.class));
                });
            } catch (IllegalArgumentException ignored) {
                // 非法 contentItemId：回退任务自身 content
            }
        }
        return dto;
    }

    /** 按 audio 数组下标为 sentences/words/items 补充 audioUrl（预签名，浏览器直接播放）。 */
    private void attachAudioUrls(ObjectNode content) {
        JsonNode audio = content.path("audio");
        if (!audio.isArray()) {
            return;
        }
        for (String field : new String[]{"sentences", "words", "items"}) {
            JsonNode items = content.path(field);
            if (!items.isArray()) {
                continue;
            }
            for (int i = 0; i < items.size() && i < audio.size(); i++) {
                String objectKey = audio.get(i).path("objectKey").asText("");
                if (!objectKey.isBlank() && items.get(i) instanceof ObjectNode node) {
                    try {
                        node.put("audioUrl", storage.presignContentUrl(objectKey));
                    } catch (Exception e) {
                        log.warn("Presign content audio failed for {}: {}", objectKey, e.getMessage());
                    }
                }
            }
        }
    }

    public Map<String, Object> taskDto(LearningTask task) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("taskId", task.getId().toString());
        dto.put("taskDate", task.getTaskDate().toString());
        dto.put("taskType", task.getTaskType());
        dto.put("title", task.getTitle());
        dto.put("durationMin", (int) task.getDurationMin());
        dto.put("priority", (int) task.getPriority());
        dto.put("status", task.getStatus());
        dto.put("locked", task.isLocked());
        dto.put("content", read(task.getContentJson()));
        return dto;
    }

    private ZoneId zoneOf(String timezone) {
        try {
            return timezone == null || timezone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timezone);
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
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
