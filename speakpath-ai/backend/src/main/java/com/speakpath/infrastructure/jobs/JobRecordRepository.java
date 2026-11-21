package com.speakpath.infrastructure.jobs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JobRecordRepository extends JpaRepository<JobRecord, UUID> {

    Optional<JobRecord> findByIdAndUserId(UUID id, UUID userId);
}
