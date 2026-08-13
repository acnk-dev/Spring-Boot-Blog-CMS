package com.aditya_nk.blogcms.client;

import com.aditya_nk.blogcms.dto.ai.AIJobExcerptRequest;
import com.aditya_nk.blogcms.dto.ai.AIJobExpandRequest;
import com.aditya_nk.blogcms.dto.ai.AIJobOutlineRequest;
import com.aditya_nk.blogcms.dto.ai.AIJobPostRequest;
import com.aditya_nk.blogcms.dto.ai.AIJobSuggestTagsRequest;
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

    public void dispatchSuggestTags(AIJobSuggestTagsRequest request) {
        try {
            aiServiceRestClient.post()
                    .uri("/internal/v1/suggest-tags")
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.error("Failed to dispatch suggest-tags job {} to AI service", request.getJobId(), e);
            throw new AIServiceUnavailableException(
                    "AI service is unavailable — could not dispatch job " + request.getJobId(), e);
        }
    }

    public void dispatchOutline(AIJobOutlineRequest request) {
        try {
            aiServiceRestClient.post()
                    .uri("/internal/v1/outline")
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.error("Failed to dispatch outline job {} to AI service", request.getJobId(), e);
            throw new AIServiceUnavailableException(
                    "AI service is unavailable — could not dispatch job " + request.getJobId(), e);
        }
    }

    public void dispatchExpand(AIJobExpandRequest request) {
        try {
            aiServiceRestClient.post()
                    .uri("/internal/v1/expand")
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.error("Failed to dispatch expand job {} to AI service", request.getJobId(), e);
            throw new AIServiceUnavailableException(
                    "AI service is unavailable — could not dispatch job " + request.getJobId(), e);
        }
    }

    public void dispatchPost(AIJobPostRequest request) {
        try {
            aiServiceRestClient.post()
                    .uri("/internal/v1/post")
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.error("Failed to dispatch post job {} to AI service", request.getJobId(), e);
            throw new AIServiceUnavailableException(
                    "AI service is unavailable — could not dispatch job " + request.getJobId(), e);
        }
    }
}
