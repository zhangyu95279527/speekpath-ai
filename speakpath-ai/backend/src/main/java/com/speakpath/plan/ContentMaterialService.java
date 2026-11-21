package com.speakpath.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.speakpath.ai.gateway.AiCapability;
import com.speakpath.ai.gateway.AiGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内容项练习材料补全：启动时后台调用 LLM 为缺少结构化材料的内容项生成
 * sentences/words/items（SCENARIO 生成话题与目标句型）并落库；
 * LLM 不可用时使用内置模板兜底，保证任务永远有可练习内容。
 */
@Service
public class ContentMaterialService {

    private static final Logger log = LoggerFactory.getLogger(ContentMaterialService.class);

    public static final String READY = "READY";
    public static final String PENDING = "PENDING";
    public static final String GENERATING = "GENERATING";
    public static final String FAILED = "FAILED";

    private final ContentItemRepository contentRepository;
    private final AiGateway aiGateway;
    private final TaskAudioService taskAudioService;
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger done = new AtomicInteger();
    private final AtomicInteger total = new AtomicInteger();

    public ContentMaterialService(ContentItemRepository contentRepository, AiGateway aiGateway,
                                  TaskAudioService taskAudioService) {
        this.contentRepository = contentRepository;
        this.aiGateway = aiGateway;
        this.taskAudioService = taskAudioService;
    }

    public boolean isGenerating() {
        return running.get();
    }

    public int getDoneCount() {
        return done.get();
    }

    public int getTotalCount() {
        return total.get();
    }

    /** 是否仍有内容项缺少可用材料（用于任务生成前的就绪判断）。 */
    @Transactional(readOnly = true)
    public boolean hasPendingMaterials() {
        return publishedItems().stream()
                .anyMatch(item -> !READY.equals(item.getMaterialStatus()) || !hasMaterial(read(item.getContentJson())));
    }

    /** 异步补全所有缺少材料的内容项；同一时间只运行一个补全任务。 */
    public void bootstrapAsync() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            List<ContentItem> todo = publishedItems().stream()
                    .filter(item -> !hasMaterial(read(item.getContentJson())))
                    .toList();
            total.set(todo.size());
            done.set(0);
            if (todo.isEmpty()) {
                log.info("Content material bootstrap: nothing to do");
                return;
            }
            log.info("Content material bootstrap started: {} item(s)", todo.size());
            for (ContentItem item : todo) {
                try {
                    generateForItem(item.getId());
                } catch (Exception e) {
                    log.warn("Content material generation failed for {}: {}", item.getId(), e.getMessage());
                }
                done.incrementAndGet();
            }
            log.info("Content material bootstrap finished: {}/{}", done.get(), total.get());
        } catch (Exception e) {
            log.warn("Content material bootstrap failed: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /** 为单个内容项生成并保存练习材料（幂等）。 */
    @Transactional
    public void generateForItem(UUID contentItemId) {
        ContentItem item = contentRepository.findById(contentItemId).orElse(null);
        if (item == null) {
            return;
        }
        ObjectNode existing = (ObjectNode) read(item.getContentJson());
        if (hasMaterial(existing)) {
            item.setMaterialStatus(READY);
            contentRepository.save(item);
            return;
        }
        item.setMaterialStatus(GENERATING);
        contentRepository.save(item);

        String instructions = existing.path("instructions").asText("");
        ObjectNode content = null;
        try {
            content = aiGenerate(item, instructions);
        } catch (Exception e) {
            log.warn("LLM content generation unavailable for {} ({}): {}",
                    item.getTitle(), item.getId(), e.getMessage());
        }
        if (content == null) {
            content = fallback(item, instructions);
        }
        item.setContentJson(content.toString());
        item.setMaterialStatus(content == null ? FAILED : READY);
        contentRepository.save(item);

        try {
            taskAudioService.ensureContentAudio(item.getId());
        } catch (Exception e) {
            log.warn("Content audio generation failed for {}: {}", item.getId(), e.getMessage());
        }
    }

    private List<ContentItem> publishedItems() {
        return contentRepository.findByStatusOrderByItemTypeAscDifficultyAsc("PUBLISHED");
    }

    /** 结构化材料判定：有练习数组，或 SCENARIO 有话题。 */
    public static boolean hasMaterial(JsonNode content) {
        if (content == null || !content.isObject()) {
            return false;
        }
        return nonEmptyArray(content.path("sentences"))
                || nonEmptyArray(content.path("words"))
                || nonEmptyArray(content.path("items"))
                || ("scenario".equals(content.path("exerciseType").asText())
                        && !content.path("topic").asText("").isBlank());
    }

    private static boolean nonEmptyArray(JsonNode node) {
        return node != null && node.isArray() && node.size() > 0;
    }

    private ObjectNode aiGenerate(ContentItem item, String instructions) {
        String itemType = item.getItemType();
        String systemPrompt = """
                You are an English speaking-practice content designer for Chinese learners.
                Create original, natural B1-B2 English practice material for one study item.
                Return a single JSON object only, no markdown, no extra text.
                Schema by item type:
                WARMUP: {"exerciseType":"shadowing","instructions":"English instruction","sentences":[{"text":"English sentence","note":"中文提示"}]}
                SKILL: {"exerciseType":"practice","instructions":"English instruction","items":[{"text":"English sentence","note":"中文要点"}]}
                  If the title is about stress or intonation, use:
                  {"exerciseType":"stress","instructions":"...","words":[{"word":"record","pattern":"RE-cord / re-CORD","example":"English example sentence"}]}
                CORRECTION: {"exerciseType":"collocation","instructions":"...","items":[{"text":"Correct English sentence","note":"common mistake -> correction, 中文说明"}]}
                QUIZ: {"exerciseType":"summary","instructions":"...","items":[{"text":"English prompt sentence","note":"中文提示"}]}
                SCENARIO: {"exerciseType":"scenario","topic":"English topic","instructions":"...","openers":["English opening line"],"targetPhrases":[{"phrase":"English phrase","meaning":"中文释义"}]}
                Always provide 4-5 entries in the array field.
                """;
        Map<String, Object> input = Map.of(
                "itemType", itemType,
                "title", item.getTitle(),
                "existingInstructions", instructions);
        JsonNode out = aiGateway.invoke(new AiGateway.Invocation(AiCapability.PLANNING,
                        systemPrompt, input, null, Duration.ofSeconds(60), "content-material-v1"),
                new AiGateway.ModelInvocationContext(null, null, null, "STANDARD")).output();
        return normalize(itemType, item.getTitle(), instructions, out);
    }

    private ObjectNode normalize(String itemType, String title, String instructions, JsonNode out) {
        if (out == null || !out.isObject()) {
            return null;
        }
        String exerciseType = out.path("exerciseType").asText("");
        if (exerciseType.isBlank()) {
            exerciseType = defaultExerciseType(itemType, title);
        }
        ObjectNode content = mapper.createObjectNode();
        content.put("exerciseType", exerciseType);
        String inst = out.path("instructions").asText("");
        content.put("instructions", inst.isBlank() ? defaultInstructions(exerciseType) : inst);

        ArrayNode sentences = cleanArray(out.path("sentences"), "text");
        ArrayNode words = cleanArray(out.path("words"), "word");
        ArrayNode items = cleanArray(out.path("items"), "text");
        if (sentences.size() > 0) {
            content.set("sentences", sentences);
        }
        if (words.size() > 0) {
            content.set("words", words);
        }
        if (items.size() > 0) {
            content.set("items", items);
        }
        if ("scenario".equals(exerciseType)) {
            content.put("topic", out.path("topic").asText(title));
            ArrayNode openers = mapper.createArrayNode();
            out.path("openers").forEach(node -> {
                String text = node.isObject() ? node.path("text").asText("") : node.asText("");
                if (!text.isBlank()) {
                    openers.add(text);
                }
            });
            content.set("openers", openers);
            content.set("targetPhrases", cleanArray(out.path("targetPhrases"), "phrase"));
        }
        return hasMaterial(content) ? content : null;
    }

    /** 只保留必需的文本字段，过滤模型输出的空条目与非法结构。 */
    private ArrayNode cleanArray(JsonNode source, String requiredField) {
        ArrayNode result = mapper.createArrayNode();
        if (source == null || !source.isArray()) {
            return result;
        }
        for (JsonNode node : source) {
            if (node.isTextual()) {
                String text = node.asText("").trim();
                if (!text.isBlank()) {
                    ObjectNode entry = result.addObject();
                    entry.put(requiredField.isBlank() ? "text" : requiredField, text);
                }
                continue;
            }
            if (!node.isObject()) {
                continue;
            }
            String key = requiredField.isBlank() ? "text" : requiredField;
            if (node.path(key).asText("").isBlank()) {
                continue;
            }
            result.add(node);
        }
        return result;
    }

    private String defaultExerciseType(String itemType, String title) {
        return switch (itemType) {
            case "WARMUP" -> "shadowing";
            case "CORRECTION" -> "collocation";
            case "QUIZ" -> "summary";
            case "SCENARIO" -> "scenario";
            default -> title != null && (title.contains("重音") || title.contains("语调")) ? "stress" : "practice";
        };
    }

    private String defaultInstructions(String exerciseType) {
        return switch (exerciseType) {
            case "shadowing" -> "Listen to the sample, then shadow each sentence and match its stress and intonation.";
            case "stress" -> "Study the stress pattern, then read the example aloud.";
            case "scenario" -> "Role-play the scenario with the AI tutor and use the target phrases.";
            default -> "Read each sentence aloud and note the highlighted expression.";
        };
    }

    /** LLM 不可用时的内置材料，保证任务内容始终可用。 */
    private ObjectNode fallback(ContentItem item, String instructions) {
        String exerciseType = defaultExerciseType(item.getItemType(), item.getTitle());
        ObjectNode content = mapper.createObjectNode();
        content.put("exerciseType", exerciseType);
        content.put("instructions", instructions == null || instructions.isBlank()
                ? defaultInstructions(exerciseType) : instructions);

        if ("shadowing".equals(exerciseType)) {
            ArrayNode sentences = content.putArray("sentences");
            sentence(sentences, "The early bird catches the worm, but the second mouse gets the cheese.",
                    "重音落在 early / worm / cheese，注意并列对比语调");
            sentence(sentences, "She sells seashells by the seashore every single summer.",
                    "s 与 sh 交替，语速放缓，保持清晰");
            sentence(sentences, "I couldn't believe how quickly the weekend had flown by.",
                    "couldn't 与 quickly 重读，flown by 连读");
            sentence(sentences, "Would you mind telling me where the nearest station is?",
                    "礼貌请求用升调，mind 与 nearest 重读");
        } else if ("stress".equals(exerciseType)) {
            ArrayNode words = content.putArray("words");
            stress(words, "record", "RE-cord (名词) / re-CORD (动词)",
                    "I need to reCORD a new REcord for my podcast.");
            stress(words, "present", "PRE-sent (名词) / pre-SENT (动词)",
                    "She will preSENT the PREsent at the ceremony.");
            stress(words, "increase", "IN-crease (名词) / in-CREASE (动词)",
                    "There was an INcrease in sales after we decided to inCREASE the budget.");
            stress(words, "economy", "e-CO-no-my / e-co-NO-mic",
                    "The eCOnomy grew thanks to ecoNOmic reforms.");
        } else if ("scenario".equals(exerciseType)) {
            content.put("topic", item.getTitle());
            ArrayNode openers = content.putArray("openers");
            openers.add("Hi! Thanks for meeting with me today.");
            openers.add("Let's get started. Could you tell me a little about yourself?");
            ArrayNode phrases = content.putArray("targetPhrases");
            phrase(phrases, "I'd like to...", "我想要……");
            phrase(phrases, "Could you clarify...?", "你能说明一下……吗？");
            phrase(phrases, "In my opinion...", "在我看来……");
            phrase(phrases, "Thanks for your time.", "感谢你的时间。");
        } else {
            ArrayNode items = content.putArray("items");
            sentence(items, "I tend to procrastinate when a deadline feels far away.",
                    "procrastinate = 拖延，耽搁");
            sentence(items, "The new policy will inevitably lead to higher prices.",
                    "inevitably = 不可避免地");
            sentence(items, "She gave a concise summary of the report in two minutes.",
                    "concise = 简洁的，简明的");
            sentence(items, "Regular exercise can alleviate stress and improve sleep.",
                    "alleviate = 缓解，减轻");
        }
        return content;
    }

    private void sentence(ArrayNode array, String text, String note) {
        ObjectNode node = array.addObject();
        node.put("text", text);
        node.put("note", note);
    }

    private void stress(ArrayNode array, String word, String pattern, String example) {
        ObjectNode node = array.addObject();
        node.put("word", word);
        node.put("pattern", pattern);
        node.put("example", example);
    }

    private void phrase(ArrayNode array, String phrase, String meaning) {
        ObjectNode node = array.addObject();
        node.put("phrase", phrase);
        node.put("meaning", meaning);
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }
}
