package com.speakpath.progress;

import com.speakpath.common.api.ApiEnvelope;
import com.speakpath.common.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/progress")
public class ProgressController {

    private final ProgressService progressService;
    private final DailyLearningReportService dailyReportService;

    public ProgressController(ProgressService progressService, DailyLearningReportService dailyReportService) {
        this.progressService = progressService;
        this.dailyReportService = dailyReportService;
    }

    @GetMapping("/summary")
    public ApiEnvelope<Map<String, Object>> summary() {
        return ApiEnvelope.ok(progressService.summary());
    }

    /** 成绩趋势：历次水平评测综合分、当前分、目标分与差距。 */
    @GetMapping("/score-trend")
    public ApiEnvelope<Map<String, Object>> scoreTrend() {
        return ApiEnvelope.ok(progressService.scoreTrend());
    }

    /** 最近 7 天每日学习评价。 */
    @GetMapping("/daily-reports")
    public ApiEnvelope<List<Map<String, Object>>> dailyReports() {
        return ApiEnvelope.ok(dailyReportService.latestReports());
    }

    /** 立即评价指定日期（默认昨天），用于补生成/调试。 */
    @PostMapping("/daily-reports/evaluate")
    public ApiEnvelope<Map<String, Object>> evaluate(@RequestParam(required = false) LocalDate date) {
        LocalDate target = date == null ? LocalDate.now().minusDays(1) : date;
        return ApiEnvelope.ok(dailyReportService.evaluate(CurrentUser.require().id(), target));
    }
}
