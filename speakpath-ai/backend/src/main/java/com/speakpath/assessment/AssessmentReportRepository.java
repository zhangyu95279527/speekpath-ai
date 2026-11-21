package com.speakpath.assessment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AssessmentReportRepository extends JpaRepository<AssessmentReport, UUID> {

    Optional<AssessmentReport> findBySessionId(UUID sessionId);

    Optional<AssessmentReport> findByIdAndSessionId(UUID id, UUID sessionId);
}
