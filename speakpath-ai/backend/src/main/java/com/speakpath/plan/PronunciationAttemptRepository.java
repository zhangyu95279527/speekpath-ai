package com.speakpath.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PronunciationAttemptRepository extends JpaRepository<PronunciationAttempt, UUID> {

    List<PronunciationAttempt> findByTaskIdAndUserIdOrderByCreatedAtDesc(UUID taskId, UUID userId);
}
