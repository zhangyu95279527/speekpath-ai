package com.speakpath.plan;

import com.speakpath.assessment.AssessmentReport;
import com.speakpath.assessment.AssessmentReportRepository;
import com.speakpath.assessment.AssessmentSession;
import com.speakpath.assessment.AssessmentSessionRepository;
import com.speakpath.correction.CorrectionItemRepository;
import com.speakpath.correction.ExamSpeakingCriteria;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 学习难度自适应：基于最近一次水平评测与 AI 口语纠错数据，决定下一个周期的内容难度与纠错重点。
 */
@Service
public class PlanAdaptationService {

    /** 难度级别 1（最基础）~ 5（最进阶）。 */
    public record Adaptation(int level, List<String> focusAreas, BigDecimal latestScore) {
    }

    private final AssessmentSessionRepository sessionRepository;
    private final AssessmentReportRepository reportRepository;
    private final CorrectionItemRepository correctionRepository;

    public PlanAdaptationService(AssessmentSessionRepository sessionRepository,
                                 AssessmentReportRepository reportRepository,
                                 CorrectionItemRepository correctionRepository) {
        this.sessionRepository = sessionRepository;
        this.reportRepository = reportRepository;
        this.correctionRepository = correctionRepository;
    }

    public Adaptation forUser(UUID userId, String examCode) {
        BigDecimal latest = latestScore(userId);
        int level = levelOf(latest, examCode);
        return new Adaptation(level, focusAreas(userId, examCode), latest);
    }

    /** 最近一次已完成测评的综合分（区间中值）；尚未测评时返回 null。 */
    public BigDecimal latestScore(UUID userId) {
        for (AssessmentSession session : sessionRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (!AssessmentSession.COMPLETED.equals(session.getStatus())) {
                continue;
            }
            AssessmentReport report = reportRepository.findBySessionId(session.getId()).orElse(null);
            if (report != null && report.getOverallLow() != null && report.getOverallHigh() != null) {
                return report.getOverallLow().add(report.getOverallHigh())
                        .divide(BigDecimal.valueOf(2), 1, RoundingMode.HALF_UP);
            }
        }
        return null;
    }

    /** 按最近测评得分占满分比例分档；没有测评时使用中间档。 */
    public int levelOf(BigDecimal score, String examCode) {
        if (score == null) {
            return 3;
        }
        double ratio = score.doubleValue() / maxScore(examCode);
        if (ratio < 0.40) return 1;
        if (ratio < 0.55) return 2;
        if (ratio < 0.70) return 3;
        if (ratio < 0.85) return 4;
        return 5;
    }

    /** AI 口语纠错中出现最多的 1-2 个考试评分维度。 */
    public List<String> focusAreas(UUID userId, String examCode) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : correctionRepository.countByCategoryForUser(userId)) {
            String code = ExamSpeakingCriteria.normalize(examCode, String.valueOf(row[0]));
            counts.merge(code, ((Number) row[1]).longValue(), Long::sum);
        }
        List<String> labels = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(2)
                .forEach(e -> labels.add(ExamSpeakingCriteria.label(examCode, e.getKey())));
        return labels;
    }

    public double maxScore(String examCode) {
        return ExamSpeakingCriteria.isToefl(examCode) ? 30.0 : 9.0;
    }
}
