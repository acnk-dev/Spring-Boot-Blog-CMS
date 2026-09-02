package com.aditya_nk.blogcms.dto.ai;

import lombok.Builder;
import lombok.Getter;

/**
 * What this monolith sends to blogcms-ai-service's POST /internal/v1/generate-image.
 * categoryName is resolved from GenerateImageRequest.categoryId by
 * AIJobServiceImpl before this is built — same reasoning as AIJobOutlineRequest.
 */
@Getter
@Builder
public class AIJobImageRequest {

    private String jobId;
    private Long userId;
    private String title;
    private String categoryName;
    private String style;
    private String additionalContext;
}
