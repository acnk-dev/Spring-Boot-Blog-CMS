package com.aditya_nk.blogcms.dto.ai;

import lombok.Builder;
import lombok.Getter;

/**
 * What this monolith sends to blogcms-ai-service's POST /internal/v1/suggest-tags.
 * Built by AIJobServiceImpl from the public TagSuggestionRequest plus a
 * generated jobId and the authenticated user's id. Mirrors (but is a
 * separate class from) the AI service's own TagSuggestionDispatchRequest —
 * see AIJobExcerptRequest for why these are two classes, not one shared
 * across the codebases.
 */
@Getter
@Builder
public class AIJobSuggestTagsRequest {

    private String jobId;
    private Long userId;
    private String content;
    private int maxTags;
}
