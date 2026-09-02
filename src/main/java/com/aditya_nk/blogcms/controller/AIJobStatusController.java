package com.aditya_nk.blogcms.controller;

import com.aditya_nk.blogcms.dto.ai.AIJobStatusResponse;
import com.aditya_nk.blogcms.service.AIJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai/jobs")
@RequiredArgsConstructor
public class AIJobStatusController {

    private final AIJobService aiJobService;

    @GetMapping("/{jobId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AIJobStatusResponse> getJobStatus(@PathVariable String jobId) {
        return ResponseEntity.ok(aiJobService.getJobStatus(jobId));
    }
}
