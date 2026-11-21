package com.speakpath.plan;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 启动后异步补全缺失的练习材料（LLM 生成并落库），不阻塞启动与健康检查。
 * 客户端通过任务就绪接口轮询生成进度。
 */
@Component
public class StartupContentBootstrap {

    private final ContentMaterialService contentMaterialService;

    public StartupContentBootstrap(ContentMaterialService contentMaterialService) {
        this.contentMaterialService = contentMaterialService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        CompletableFuture.runAsync(contentMaterialService::bootstrapAsync);
    }
}
