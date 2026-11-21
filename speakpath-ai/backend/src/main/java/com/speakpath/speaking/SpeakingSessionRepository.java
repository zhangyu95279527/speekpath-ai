package com.speakpath.speaking;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpeakingSessionRepository extends JpaRepository<SpeakingSession, UUID> {

    Optional<SpeakingSession> findByIdAndUserId(UUID id, UUID userId);

    List<SpeakingSession> findByUserIdOrderByCreatedAtDesc(UUID userId);

    long countByUserIdAndStatus(UUID userId, String status);

    List<SpeakingSession> findByUserIdAndStatusAndStartedAtBefore(UUID userId, String status, Instant startedAt);

    Optional<SpeakingSession> findFirstByUserIdAndStatusInOrderByCreatedAtDesc(UUID userId, List<String> statuses);

    Optional<SpeakingSession> findFirstByUserIdAndTaskIdAndStatusInOrderByCreatedAtDesc(UUID userId, UUID taskId, List<String> statuses);

    Optional<SpeakingSession> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<SpeakingSession> findFirstByUserIdAndTaskIdOrderByCreatedAtDesc(UUID userId, UUID taskId);

    /** 行锁读取：串行化轮次序号分配（用户消息与开场白生成并发时避免唯一约束冲突）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SpeakingSession s WHERE s.id = :id")
    Optional<SpeakingSession> lockById(UUID id);
}
