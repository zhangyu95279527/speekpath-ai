package com.speakpath.exam;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ExamTemplateRepository extends JpaRepository<ExamTemplate, UUID> {

    @Query("""
            SELECT t FROM ExamTemplate t
            WHERE t.examCode = :examCode AND t.status = 'PUBLISHED'
            ORDER BY t.version DESC
            """)
    List<ExamTemplate> findPublishedByExamCode(String examCode);

    @Query("SELECT DISTINCT t.examCode FROM ExamTemplate t WHERE t.status = 'PUBLISHED'")
    List<String> findPublishedExamCodes();
}
