package com.speakpath.exam;

import com.speakpath.common.api.ApiEnvelope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/exams")
public class ExamController {

    private final ExamCatalogService catalogService;

    public ExamController(ExamCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public ApiEnvelope<List<Map<String, Object>>> catalog() {
        return ApiEnvelope.ok(catalogService.catalog());
    }
}
