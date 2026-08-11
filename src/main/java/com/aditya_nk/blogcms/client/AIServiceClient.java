package com.aditya_nk.blogcms.client;

import com.aditya_nk.blogcms.dto.ai.AIJobExcerptRequest;
import com.aditya_nk.blogcms.exception.AIServiceUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Fire-and-forget dispatcher to blogcms-ai-service. Posts the job and
 * returns as soon as the AI service acknowledges receipt (202) — never
 * waits for the actual generation to finish. See
 * plans/ai-microservice-langchain4j.md ("Async job persistence") for why
 * this call doesn't parse or return a result body.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AIServiceClient {

    private final RestClient aiServiceRestClient;

    public void dispatchExcerpt(AIJobExcerptRequest request) {
        try {
            aiServiceRestClient.post()
                    .uri("/internal/v1/excerpt")
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.error("Failed to dispatch excerpt job {} to AI service", request.getJobId(), e);
            throw new AIServiceUnavailableException(
                    "AI service is unavailable — could not dispatch job " + request.getJobId(), e);
        }
    }
}
