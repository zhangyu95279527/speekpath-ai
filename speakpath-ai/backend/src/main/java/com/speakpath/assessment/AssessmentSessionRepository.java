package com.speakpath.assessment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssessmentSessionRepository extends JpaRepository<AssessmentSession, UUID> {

    Optional<AssessmentSession> findByIdAndUserId(UUID id, UUID userId);

    List<AssessmentSession> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
