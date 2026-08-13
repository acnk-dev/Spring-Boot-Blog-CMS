package com.aditya_nk.blogcms.service;

import com.aditya_nk.blogcms.dto.ai.AIJobResponse;
import com.aditya_nk.blogcms.dto.ai.AIJobStatusResponse;
import com.aditya_nk.blogcms.dto.ai.ExcerptRequest;
import com.aditya_nk.blogcms.dto.ai.ExpandContentRequest;
import com.aditya_nk.blogcms.dto.ai.GenerateImageRequest;
import com.aditya_nk.blogcms.dto.ai.GenerateOutlineRequest;
import com.aditya_nk.blogcms.dto.ai.GeneratePostRequest;
import com.aditya_nk.blogcms.dto.ai.TagSuggestionRequest;

public interface AIJobService {

    /**
     * Generates a jobId, writes PENDING to Redis, dispatches to the AI
     * service, and returns immediately — does not wait for generation.
     */
    AIJobResponse dispatchExcerptJob(ExcerptRequest request, Long userId);

    /**
     * Same pattern as dispatchExcerptJob — tag suggestion is also
     * category-agnostic, so no category-resolution step is needed here.
     */
    AIJobResponse dispatchSuggestTagsJob(TagSuggestionRequest request, Long userId);

    /**
     * Resolves request.categoryId -> Category.getName() before dispatching —
     * the AI service never sees a categoryId, only the resolved name. First
     * of the dispatch methods that needs this (excerpt/suggest-tags are
     * category-agnostic). Throws ResourceNotFoundException if the category
     * doesn't exist.
     */
    AIJobResponse dispatchOutlineJob(GenerateOutlineRequest request, Long userId);

    /**
     * Same category-resolution step as dispatchOutlineJob.
     */
    AIJobResponse dispatchExpandJob(ExpandContentRequest request, Long userId);

    /**
     * Same category-resolution step as dispatchOutlineJob. outline is
     * passed through unchanged (the human-reviewed section list from an
     * earlier /outline call).
     */
    AIJobResponse dispatchPostJob(GeneratePostRequest request, Long userId);

    /**
     * Same category-resolution step as dispatchOutlineJob. The 2-usage-credit
     * charge for image generation is applied in AIController before this is
     * called, unchanged from before this migration.
     */
    AIJobResponse dispatchImageJob(GenerateImageRequest request, Long userId);

    /**
     * Reads job status: Redis first (fast path while PENDING), falling
     * back to the ai_jobs table once the job has finished (Redis only ever
     * holds the bare status, not the result payload) or if the Redis key
     * has already expired.
     */
    AIJobStatusResponse getJobStatus(String jobId);
}
