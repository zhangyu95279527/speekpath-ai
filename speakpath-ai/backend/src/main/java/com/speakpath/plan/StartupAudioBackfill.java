package com.speakpath.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 启动后台补齐跟读类内容项的示范音频（不阻塞启动与健康检查）。
 */
@Component
public class StartupAudioBackfill {

    private static final Logger log = LoggerFactory.getLogger(StartupAudioBackfill.class);

    private final TaskAudioService taskAudioService;

    public StartupAudioBackfill(TaskAudioService taskAudioService) {
        this.taskAudioService = taskAudioService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        CompletableFuture.runAsync(() -> {
            try {
                taskAudioService.backfillPublished();
            } catch (Exception e) {
                log.warn("Startup audio backfill failed: {}", e.getMessage());
            }
        });
    }
}
