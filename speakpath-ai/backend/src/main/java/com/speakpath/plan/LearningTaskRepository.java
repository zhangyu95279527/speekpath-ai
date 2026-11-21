package com.speakpath.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LearningTaskRepository extends JpaRepository<LearningTask, UUID> {

    List<LearningTask> findByUserIdAndTaskDateOrderByPriorityAsc(UUID userId, LocalDate taskDate);

    /** 仅当前计划版本的任务：避免重排后旧版本任务与当前版本叠加。 */
    List<LearningTask> findByUserIdAndTaskDateAndPlanVersionIdOrderByPriorityAsc(
            UUID userId, LocalDate taskDate, UUID planVersionId);

    List<LearningTask> findByUserIdAndStatus(UUID userId, String status);

    List<LearningTask> findByPlanVersionIdOrderByTaskDateAscPriorityAsc(UUID planVersionId);

    Optional<LearningTask> findByIdAndUserId(UUID id, UUID userId);

    List<LearningTask> findByUserIdAndTaskDateGreaterThanEqualOrderByTaskDateAsc(UUID userId, LocalDate from);
}
