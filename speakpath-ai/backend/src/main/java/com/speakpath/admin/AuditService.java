package com.speakpath.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.speakpath.common.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 管理审计服务：所有管理员变更记录操作者、前后值与理由。
 */
@Service
public class AuditService {

    private final AdminAuditLogRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditService(AdminAuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(String action, String targetType, String targetId,
                       Object before, Object after, String reason) {
        repository.save(new AdminAuditLog(
                CurrentUser.require().id(),
                action, targetType, targetId,
                json(before), json(after), reason));
    }

    @Transactional(readOnly = true)
    public java.util.List<AdminAuditLog> recent() {
        return repository.findTop50ByOrderByCreatedAtDesc();
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
