package com.speakpath.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.speakpath.ai.gateway.AiCapability;
import com.speakpath.ai.speech.SpeechGateway;
import com.speakpath.ai.speech.SpeechRequest;
import com.speakpath.ai.speech.SpeechResult;
import com.speakpath.infrastructure.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 任务示范音频预生成：内容项级缓存（同一内容共享一份音频），
 * 在任务创建（计划生成）时触发；缺失时后台补齐，任务详情直接返回预签名 URL。
 */
@Service
public class TaskAudioService {

    private static final Logger log = LoggerFactory.getLogger(TaskAudioService.class);

    private final ContentItemRepository contentRepository;
    private final SpeechGateway speechGateway;
    private final StorageService storage;
    private final ObjectMapper mapper = new ObjectMapper();

    public TaskAudioService(ContentItemRepository contentRepository, SpeechGateway speechGateway,
                            StorageService storage) {
        this.contentRepository = contentRepository;
        this.speechGateway = speechGateway;
        this.storage = storage;
    }

    /**
     * 幂等生成内容项示范音频：exerciseType=shadowing 取 sentences[].text，
     * exerciseType=stress 取 words[].example；audio 数组与下标对齐写入 content_json。
     */
    @Transactional
    public void ensureContentAudio(UUID contentItemId) {
        ContentItem item = contentRepository.findById(contentItemId).orElse(null);
        if (item == null) {
            return;
        }
        ObjectNode content = (ObjectNode) read(item.getContentJson());
        String type = content.path("exerciseType").asText("");
        List<String> texts = new ArrayList<>();
        if ("shadowing".equals(type)) {
            content.path("sentences").forEach(s -> texts.add(s.path("text").asText("")));
        } else if ("stress".equals(type)) {
            content.path("words").forEach(w -> {
                String example = w.path("example").asText("");
                texts.add(example.isBlank() ? w.path("word").asText("") : example);
            });
        } else {
            // 通用 items 结构（词汇/同义替换/搭配/总结等）
            content.path("items").forEach(s -> texts.add(s.path("text").asText("")));
        }
        if (texts.isEmpty() || content.hasNonNull("audio")) {
            return;
        }
        ArrayNode audio = mapper.createArrayNode();
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text.isBlank()) {
                audio.addObject();
                continue;
            }
            try {
                SpeechResult tts = speechGateway.invoke(AiCapability.TTS, SpeechRequest.tts(text, null));
                if (tts.audio() == null || tts.audio().length == 0) {
                    log.warn("Content audio TTS returned no audio for item={} idx={}", contentItemId, i);
                    audio.addObject();
                    continue;
                }
                String objectKey = "content-audio/" + contentItemId + "/" + i + ".mp3";
                storage.putObject(objectKey, tts.audio(), "audio/mpeg");
                audio.addObject().put("objectKey", objectKey);
                log.info("Content audio generated: {} ({} bytes)", objectKey, tts.audio().length);
            } catch (Exception e) {
                log.warn("Content audio generation failed item={} idx={}: {}", contentItemId, i, e.getMessage());
                audio.addObject();
            }
        }
        content.set("audio", audio);
        item.setContentJson(content.toString());
        contentRepository.save(item);
    }

    /** 补齐所有已发布跟读类内容项的示范音频（启动时后台执行）。 */
    public void backfillPublished() {
        for (ContentItem item : contentRepository.findByStatusOrderByItemTypeAscDifficultyAsc("PUBLISHED")) {
            try {
                ensureContentAudio(item.getId());
            } catch (Exception e) {
                log.warn("Content audio backfill failed for {}: {}", item.getId(), e.getMessage());
            }
        }
        log.info("Content audio backfill finished");
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }
}
