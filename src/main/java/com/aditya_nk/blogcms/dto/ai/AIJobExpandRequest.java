package com.aditya_nk.blogcms.dto.ai;

import lombok.Builder;
import lombok.Getter;

/**
 * What this monolith sends to blogcms-ai-service's POST /internal/v1/expand.
 * categoryName is resolved from ExpandContentRequest.categoryId by
 * AIJobServiceImpl before this is built — same reasoning as AIJobOutlineRequest.
 */
@Getter
@Builder
public class AIJobExpandRequest {

    private String jobId;
    private Long userId;
    private String content;
    private String categoryName;
    private String focusAreas;
}
