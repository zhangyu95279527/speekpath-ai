package com.speakpath.ai.gateway;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按能力隔离的简易熔断器：连续失败达到阈值后打开，冷却期后恢复。
 * TTS 故障不影响测评能力（规格书 §6.4）。
 */
@Component
public class SimpleCircuitBreaker {

    private static final int FAILURE_THRESHOLD = 5;
    private static final long OPEN_MILLIS = 30_000;

    private static final class State {
        final AtomicInteger failures = new AtomicInteger();
        volatile long openedAt;

        boolean isOpen() {
            return openedAt > 0 && System.currentTimeMillis() - openedAt < OPEN_MILLIS;
        }
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();

    public boolean isOpen(String capability, String provider) {
        State state = states.get(key(capability, provider));
        if (state == null) {
            return false;
        }
        if (state.isOpen()) {
            return true;
        }
        if (state.openedAt > 0) {
            // 冷却期结束，半开复位
            state.failures.set(0);
            state.openedAt = 0;
        }
        return false;
    }

    public void recordSuccess(String capability, String provider) {
        State state = states.computeIfAbsent(key(capability, provider), k -> new State());
        state.failures.set(0);
        state.openedAt = 0;
    }

    public void recordFailure(String capability, String provider) {
        State state = states.computeIfAbsent(key(capability, provider), k -> new State());
        if (state.failures.incrementAndGet() >= FAILURE_THRESHOLD) {
            state.openedAt = System.currentTimeMillis();
        }
    }

    private String key(String capability, String provider) {
        return capability + ":" + provider;
    }
}
