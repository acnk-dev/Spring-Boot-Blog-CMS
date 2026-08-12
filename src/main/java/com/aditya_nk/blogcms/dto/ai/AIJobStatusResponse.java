package com.aditya_nk.blogcms.dto.ai;

import lombok.Builder;
import lombok.Getter;

/**
 * Body for GET /api/v1/ai/jobs/{jobId}. `result` holds whatever JSON the AI
 * service wrote (e.g. {"excerpt": "..."} for an excerpt job, {"tags": [...]}
 * for a suggest-tags job) — deliberately typed as Object rather than String,
 * so Jackson embeds it as a real nested JSON object/array in the response
 * instead of an escaped string. AIJobServiceImpl parses the raw JSON string
 * from ai_jobs into this via ObjectMapper before building the response. No
 * single typed field works here since the shape differs per job type and
 * this endpoint is generic across all of them.
 */
@Getter
@Builder
public class AIJobStatusResponse {

    private String jobId;
    private String status;
    private Object result;
    private String error;
}
