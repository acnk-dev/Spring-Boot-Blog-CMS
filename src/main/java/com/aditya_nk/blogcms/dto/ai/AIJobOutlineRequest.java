package com.aditya_nk.blogcms.dto.ai;

import lombok.Builder;
import lombok.Getter;

/**
 * What this monolith sends to blogcms-ai-service's POST /internal/v1/outline.
 * categoryName is resolved from GenerateOutlineRequest.categoryId by
 * AIJobServiceImpl before this is built — the AI service never sees a
 * categoryId or touches CategoryRepository. Mirrors (but is a separate
 * class from) the AI service's own OutlineDispatchRequest — see
 * AIJobExcerptRequest for why these are two classes, not one shared
 * across the codebases.
 */
@Getter
@Builder
public class AIJobOutlineRequest {

    private String jobId;
    private Long userId;
    private String topic;
    private String categoryName;
    private String targetAudience;
    private String additionalInstructions;
}
