package com.speakpath.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 每日情景主题：以发布的内容库中 SCENARIO 项为当天主题来源。
 * 每日任务内容与情景口语共用同一主题，保证训练内容一致。
 */
@Service
public class DailyThemeService {

    private final ContentItemRepository contentRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    public DailyThemeService(ContentItemRepository contentRepository) {
        this.contentRepository = contentRepository;
    }

    /** 当天选用的情景内容项（按日期稳定轮换）。 */
    public Optional<ContentItem> scenarioFor(LocalDate date) {
        List<ContentItem> scenarios = contentRepository.findByStatusOrderByItemTypeAscDifficultyAsc("PUBLISHED")
                .stream()
                .filter(item -> "SCENARIO".equals(item.getItemType()))
                .filter(item -> ContentMaterialService.READY.equals(item.getMaterialStatus()))
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .toList();
        if (scenarios.isEmpty()) {
            return Optional.empty();
        }
        int index = (int) Math.floorMod(date.toEpochDay(), scenarios.size());
        return Optional.of(scenarios.get(index));
    }

    /** 当天情景主题（用于口语会话与任务内容生成）。 */
    public String topicFor(LocalDate date) {
        return scenarioFor(date).map(this::topicOf).orElse(null);
    }

    public String topicOf(ContentItem scenario) {
        if (scenario == null) {
            return null;
        }
        try {
            JsonNode content = mapper.readTree(scenario.getContentJson());
            String topic = content.path("topic").asText("").trim();
            if (!topic.isBlank()) {
                return topic;
            }
        } catch (Exception ignored) {
            // 内容损坏时回退标题
        }
        return scenario.getTitle();
    }
}
