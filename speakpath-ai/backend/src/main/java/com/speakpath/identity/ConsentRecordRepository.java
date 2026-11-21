package com.speakpath.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, UUID> {

    List<ConsentRecord> findByUserIdAndConsentTypeOrderByCreatedAtDesc(UUID userId, String consentType);
}
