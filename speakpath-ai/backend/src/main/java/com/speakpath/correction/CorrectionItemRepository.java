package com.speakpath.correction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface CorrectionItemRepository extends JpaRepository<CorrectionItem, UUID> {

    List<CorrectionItem> findBySpeakingTurnIdInOrderByCreatedAtAsc(List<UUID> turnIds);

    @Query(value = """
            SELECT c.category, COUNT(*) FROM correction_items c
            JOIN speaking_turns t ON t.id = c.speaking_turn_id
            JOIN speaking_sessions s ON s.id = t.session_id
            WHERE s.user_id = :userId
            GROUP BY c.category
            """, nativeQuery = true)
    List<Object[]> countByCategoryForUser(UUID userId);
}
