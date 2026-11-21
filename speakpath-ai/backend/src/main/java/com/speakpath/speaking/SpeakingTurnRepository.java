package com.speakpath.speaking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpeakingTurnRepository extends JpaRepository<SpeakingTurn, UUID> {

    List<SpeakingTurn> findBySessionIdOrderBySequenceNoAsc(UUID sessionId);

    long countBySessionId(UUID sessionId);

    /** 会话的开场白轮次（metadataJson 含 "opening"），按顺序取第一条。 */
    default Optional<SpeakingTurn> findOpeningTurn(UUID sessionId) {
        return findBySessionIdOrderBySequenceNoAsc(sessionId).stream()
                .filter(t -> t.getMetadataJson() != null && t.getMetadataJson().contains("\"opening\""))
                .findFirst();
    }
}
