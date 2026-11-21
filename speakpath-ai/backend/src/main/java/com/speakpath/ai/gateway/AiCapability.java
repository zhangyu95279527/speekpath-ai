package com.speakpath.ai.gateway;

/**
 * AI 能力目录（规格书 §6.1）：按能力独立配置与路由，不能以一个默认模型覆盖全部能力。
 */
public enum AiCapability {
    REALTIME, ASR, TTS, PRONUNCIATION, ASSESSMENT, PLANNING, CORRECTION, TUTOR
}
