package com.aditya_nk.blogcms.service;

import com.aditya_nk.blogcms.dto.ai.AIJobResponse;
import com.aditya_nk.blogcms.dto.ai.AIJobStatusResponse;
import com.aditya_nk.blogcms.dto.ai.ExcerptRequest;

public interface AIJobService {

    /**
     * Generates a jobId, writes PENDING to Redis, dispatches to the AI
     * service, and returns immediately — does not wait for generation.
     */
    AIJobResponse dispatchExcerptJob(ExcerptRequest request, Long userId);

    /**
     * Reads job status: Redis first (fast path while PENDING), falling
     * back to the ai_jobs table once the job has finished (Redis only ever
     * holds the bare status, not the result payload) or if the Redis key
     * has already expired.
     */
    AIJobStatusResponse getJobStatus(String jobId);
}
