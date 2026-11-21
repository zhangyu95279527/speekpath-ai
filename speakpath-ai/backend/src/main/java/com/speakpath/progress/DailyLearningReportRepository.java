package com.speakpath.progress;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DailyLearningReportRepository extends JpaRepository<DailyLearningReport, UUID> {

    Optional<DailyLearningReport> findByUserIdAndReportDate(UUID userId, LocalDate reportDate);

    List<DailyLearningReport> findByUserIdAndReportDateGreaterThanEqualOrderByReportDateDesc(
            UUID userId, LocalDate fromDate);
}
