package com.speakpath.plan;

import com.speakpath.common.api.ApiEnvelope;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 计划与任务 API：生成（202+jobId）、当前计划、今日任务、任务调整、重排。
 */
@RestController
@RequestMapping("/api/v1")
public class PlanController {

    private final PlanGenerationService generationService;
    private final PlanRescheduler rescheduler;
    private final TaskPracticeService practiceService;

    public PlanController(PlanGenerationService generationService, PlanRescheduler rescheduler,
                          TaskPracticeService practiceService) {
        this.generationService = generationService;
        this.rescheduler = rescheduler;
        this.practiceService = practiceService;
    }

    public record CreatePlanRequest(UUID reportId, LocalDate startDate, LocalDate endDate,
                                    int dailyMinutes, List<Integer> restDays, String timezone,
                                    Map<String, Object> lockedPreferences) {
    }

    @PostMapping("/plans")
    public ApiEnvelope<Map<String, Object>> create(@RequestBody CreatePlanRequest request) {
        return ApiEnvelope.ok(generationService.create(request.reportId(), request.startDate(), request.endDate(),
                request.dailyMinutes(), request.restDays(), request.timezone(), request.lockedPreferences()));
    }

    @GetMapping("/plans/active")
    public ApiEnvelope<Map<String, Object>> active() {
        return ApiEnvelope.ok(generationService.activePlan());
    }

    @GetMapping("/tasks")
    public ApiEnvelope<List<Map<String, Object>>> tasks(@RequestParam(required = false) LocalDate date,
                                                        @RequestParam(required = false) String timezone) {
        return ApiEnvelope.ok(generationService.tasks(date, timezone));
    }

    /** 任务内容生成就绪状态：客户端轮询直至 ready=true。 */
    @GetMapping("/tasks/readiness")
    public ApiEnvelope<Map<String, Object>> readiness(@RequestParam(required = false) LocalDate date,
                                                      @RequestParam(required = false) String timezone) {
        return ApiEnvelope.ok(generationService.taskReadiness(date, timezone));
    }

    @GetMapping("/tasks/{id}")
    public ApiEnvelope<Map<String, Object>> taskDetail(@PathVariable UUID id) {
        return ApiEnvelope.ok(generationService.taskDetail(id));
    }

    /** 提交一次跟读评测结果（可对同一内容多次评测），返回 attempt + 改进意见。 */
    @PostMapping("/tasks/{id}/pronunciation")
    public ApiEnvelope<Map<String, Object>> submitPronunciation(@PathVariable UUID id,
                                                                @RequestBody Map<String, Object> body) {
        return ApiEnvelope.ok(practiceService.submit(id, body));
    }

    /** 该任务的跟读评测历史（最新在前）。 */
    @GetMapping("/tasks/{id}/pronunciation")
    public ApiEnvelope<List<Map<String, Object>>> pronunciationAttempts(@PathVariable UUID id) {
        return ApiEnvelope.ok(practiceService.attempts(id));
    }

    @GetMapping("/tasks/upcoming")
    public ApiEnvelope<List<Map<String, Object>>> upcoming(@RequestParam(defaultValue = "7") int days) {
        return ApiEnvelope.ok(generationService.upcomingTasks(days));
    }

    public record PatchTaskRequest(String action, LocalDate targetDate, String reason) {
    }

    @PatchMapping("/tasks/{id}")
    public ApiEnvelope<Map<String, Object>> patch(@PathVariable UUID id, @RequestBody PatchTaskRequest request) {
        return ApiEnvelope.ok(rescheduler.patchTask(id, request.action(), request.targetDate(), request.reason()));
    }

    @PostMapping("/plans/recalculate")
    public ApiEnvelope<Map<String, Object>> recalculate(@RequestBody(required = false) Map<String, Object> body) {
        String trigger = body == null ? null : (String) body.getOrDefault("trigger", "USER_REQUEST");
        return ApiEnvelope.ok(rescheduler.recalculate(trigger));
    }

    public record ReplanRequest(String examCode, java.math.BigDecimal targetScore, Integer dailyMinutes) {
    }

    /** 重新规划：更新目标考试/目标分数/每日时长，异步重排未来 7 天并按最新水平调整难度。 */
    @PostMapping("/plans/replan")
    public ApiEnvelope<Map<String, Object>> replan(@RequestBody ReplanRequest request) {
        return ApiEnvelope.ok(rescheduler.replan(request.examCode(), request.targetScore(), request.dailyMinutes()));
    }
}
