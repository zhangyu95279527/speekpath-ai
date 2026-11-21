package com.speakpath.exam;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QuestionBankRepository extends JpaRepository<QuestionBankItem, UUID> {

    List<QuestionBankItem> findByExamCodeAndStatusOrderBySectionCodeAscDifficultyAsc(String examCode, String status);
}
