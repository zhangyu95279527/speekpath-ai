package com.speakpath.ai.speech;

import com.speakpath.ai.gateway.AiCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 语音能力统一入口：按能力读取 enable + provider 配置，路由到对应厂商，失败回退 MOCK。
 * 配置缺失或 enabled=false 时始终走 MOCK，保证端到端可运行。
 */
@Service
public class SpeechGateway {

    private static final Logger log = LoggerFactory.getLogger(SpeechGateway.class);

    private final SpeechProperties properties;
    private final List<SpeechProvider> providers;
    private final SpeechProvider mockProvider;

    public SpeechGateway(SpeechProperties properties, List<SpeechProvider> providers) {
        this.properties = properties;
        this.providers = providers;
        this.mockProvider = providers.stream()
                .filter(p -> "MOCK".equalsIgnoreCase(p.vendorCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("MockSpeechProvider not registered"));
    }

    public SpeechResult invoke(AiCapability capability, SpeechRequest request) {
        SpeechProperties.CapabilityConfig cfg = properties.forCapability(capability);
        String vendor = resolveVendor(cfg);
        if (vendor == null || mockProvider.vendorCode().equalsIgnoreCase(vendor)) {
            return mockProvider.invoke(request, mockConfig());
        }
        SpeechProvider provider = providerFor(vendor, capability);
        if (provider == null) {
            log.warn("No speech provider for vendor={} capability={}, fallback to MOCK", vendor, capability);
            return mockProvider.invoke(request, mockConfig());
        }
        try {
            return provider.invoke(request, cfg.configFor(vendor, 15000));
        } catch (Exception e) {
            log.warn("Speech vendor={} capability={} failed: {}, fallback to MOCK",
                    vendor, capability, e.getMessage());
            return mockProvider.invoke(request, mockConfig());
        }
    }

    private String resolveVendor(SpeechProperties.CapabilityConfig cfg) {
        if (cfg == null) {
            return "MOCK";
        }
        String provider = cfg.getProvider();
        if (provider == null || provider.isBlank() || "mock".equalsIgnoreCase(provider)) {
            return "MOCK";
        }
        return provider.toUpperCase();
    }

    private SpeechProvider providerFor(String vendor, AiCapability capability) {
        return providers.stream()
                .filter(p -> p.vendorCode().equalsIgnoreCase(vendor))
                .filter(p -> p.supports(capability))
                .findFirst()
                .orElse(null);
    }

    private SpeechConfig mockConfig() {
        return new SpeechConfig("MOCK", null, null, null, null, Map.of(), 5000);
    }
}
