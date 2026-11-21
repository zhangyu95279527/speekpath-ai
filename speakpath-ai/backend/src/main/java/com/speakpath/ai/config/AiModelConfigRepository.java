package com.speakpath.ai.config;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiModelConfigRepository extends JpaRepository<AiModelConfig, UUID> {

    List<AiModelConfig> findByCapabilityAndEnabledTrueAndStatusOrderByPriorityAsc(String capability, String status);

    List<AiModelConfig> findByCapabilityOrderByPriorityAsc(String capability);

    Optional<AiModelConfig> findFirstByCapabilityAndStatusOrderByVersionDesc(String capability, String status);
}
