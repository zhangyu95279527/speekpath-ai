package com.speakpath.assessment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssessmentTurnRepository extends JpaRepository<AssessmentTurn, UUID> {

    List<AssessmentTurn> findBySessionIdOrderBySequenceNoAsc(UUID sessionId);

    Optional<AssessmentTurn> findBySessionIdAndSequenceNo(UUID sessionId, short sequenceNo);

    long countBySessionId(UUID sessionId);
}
