package com.aditya_nk.blogcms.dto.ai;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * What this monolith sends to blogcms-ai-service's POST /internal/v1/post.
 * categoryName is resolved from GeneratePostRequest.categoryId by
 * AIJobServiceImpl before this is built — same reasoning as
 * AIJobOutlineRequest. outline is passed through unchanged (the
 * human-reviewed section list from an earlier /outline call).
 */
@Getter
@Builder
public class AIJobPostRequest {

    private String jobId;
    private Long userId;
    private String topic;
    private String categoryName;
    private List<String> outline;
    private String additionalInstructions;
}
