package com.aditya_nk.blogcms.dto.ai;

import lombok.Builder;
import lombok.Getter;

/**
 * What this monolith sends to blogcms-ai-service's POST /internal/v1/excerpt.
 * Built by AIJobServiceImpl from the public ExcerptRequest plus a generated
 * jobId and the authenticated user's id — neither of which the frontend
 * supplies. Mirrors (but is a separate class from) the AI service's own
 * ExcerptDispatchRequest, which deserializes this same JSON on its end —
 * see plans/ai-microservice-langchain4j.md for why these are two classes,
 * not one shared across the codebases.
 */
@Getter
@Builder
public class AIJobExcerptRequest {

    private String jobId;
    private Long userId;
    private String content;
    private int maxLength;
}
