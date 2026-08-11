package com.aditya_nk.blogcms.dto.ai;

import lombok.Builder;
import lombok.Getter;

/**
 * Body for GET /api/v1/ai/jobs/{jobId}. `result` is the raw JSON string
 * written by the AI service (e.g. {"excerpt": "..."} for an excerpt job) —
 * passed through as-is rather than parsed into a typed field, since the
 * shape differs per job type and this endpoint is generic across all of them.
 */
@Getter
@Builder
public class AIJobStatusResponse {

    private String jobId;
    private String status;
    private String result;
    private String error;
}
