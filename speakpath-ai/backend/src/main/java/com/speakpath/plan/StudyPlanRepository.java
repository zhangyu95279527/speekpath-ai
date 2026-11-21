package com.speakpath.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StudyPlanRepository extends JpaRepository<StudyPlan, UUID> {

    Optional<StudyPlan> findFirstByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, String status);

    Optional<StudyPlan> findByIdAndUserId(UUID id, UUID userId);
}
