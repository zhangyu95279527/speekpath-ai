package com.speakpath.infrastructure.jobs;

import com.speakpath.common.api.ApiException;
import com.speakpath.common.api.ErrorCode;
import com.speakpath.common.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 异步任务编排：创建即返回 jobId（202），执行体在独立线程池运行，任务必须幂等。
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRecordRepository repository;

    public JobService(JobRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public JobRecord create(String jobType, String idempotencyKey) {
        JobRecord job = new JobRecord(CurrentUser.id(), jobType, idempotencyKey);
        return repository.save(job);
    }

    @Transactional
    public JobRecord createFor(UUID userId, String jobType, String idempotencyKey) {
        JobRecord job = new JobRecord(userId, jobType, idempotencyKey);
        return repository.save(job);
    }

    /**
     * 提交异步任务：{@code work} 返回 {@code [resultRef, resultJson]}。
     * 注意：必须由其他 Bean 调用（走 @Async 代理），不可在本类内部自调用。
     * 异步线程可能早于调用方事务提交，因此对 job 行做可见性重试。
     * 不使用外层事务：业务执行失败时仍要能把 job 标记为 FAILED，避免长期停留在 QUEUED。
     */
    @Async("jobExecutor")
    public void runAsync(JobRecord job, Supplier<Map.Entry<String, String>> work) {
        JobRecord managed = awaitVisible(job.getId());
        managed.markRunning();
        repository.saveAndFlush(managed);
        try {
            Map.Entry<String, String> result = work.get();
            JobRecord current = awaitVisible(job.getId());
            current.markSucceeded(result.getKey(), result.getValue(), (short) 100);
            repository.save(current);
        } catch (Exception e) {
            log.error("Job {} failed", job.getId(), e);
            JobRecord current = awaitVisible(job.getId());
            current.markFailed("{\"error\":\"" + sanitize(e.getMessage()) + "\"}");
            repository.save(current);
        }
    }

    private JobRecord awaitVisible(UUID jobId) {
        for (int i = 0; i < 20; i++) {
            Optional<JobRecord> found = repository.findById(jobId);
            if (found.isPresent()) {
                return found.get();
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new NoSuchElementException("Job not visible yet: " + jobId);
    }

    @Transactional(readOnly = true)
    public JobRecord get(UUID jobId) {
        JobRecord job = repository.findById(jobId)
                .orElseThrow(() -> ApiException.notFound("Job"));
        if (!job.getUserId().equals(CurrentUser.id()) && !CurrentUser.require().isAdmin()) {
            throw ApiException.forbidden("You cannot access this job");
        }
        return job;
    }

    public Map<String, Object> toDto(JobRecord job) {
        return Map.of(
                "jobId", job.getId().toString(),
                "status", job.getStatus(),
                "progress", job.getProgress(),
                "resultRef", job.getResultRef() == null ? "" : job.getResultRef());
    }

    private String sanitize(String message) {
        if (message == null) {
            return "unknown";
        }
        return message.replace("\"", "'").replace("\\", " ").substring(0, Math.min(300, message.length()));
    }
}
