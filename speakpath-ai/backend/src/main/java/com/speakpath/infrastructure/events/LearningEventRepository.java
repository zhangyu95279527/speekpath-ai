package com.speakpath.infrastructure.events;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LearningEventRepository extends JpaRepository<LearningEvent, UUID> {

    List<LearningEvent> findTop100ByUserIdOrderByOccurredAtDesc(UUID userId);

    long countByUserIdAndEventType(UUID userId, String eventType);
}
