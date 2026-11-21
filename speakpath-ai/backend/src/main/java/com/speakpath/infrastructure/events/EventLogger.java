package com.speakpath.infrastructure.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class EventLogger {

    private final LearningEventRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();

    public EventLogger(LearningEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void log(UUID userId, String eventType, Map<String, Object> payload) {
        try {
            repository.save(new LearningEvent(userId, eventType, mapper.writeValueAsString(payload)));
        } catch (Exception e) {
            // 事件记录失败不影响主流程
        }
    }
}
