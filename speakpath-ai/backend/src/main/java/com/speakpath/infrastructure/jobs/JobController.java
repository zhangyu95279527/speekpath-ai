package com.speakpath.infrastructure.jobs;

import com.speakpath.common.api.ApiEnvelope;
import com.speakpath.common.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping("/{id}")
    public ApiEnvelope<java.util.Map<String, Object>> get(@PathVariable UUID id) {
        return ApiEnvelope.ok(jobService.toDto(jobService.get(id)));
    }
}
