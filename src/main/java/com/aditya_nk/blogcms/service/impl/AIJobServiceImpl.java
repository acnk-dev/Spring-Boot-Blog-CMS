package com.aditya_nk.blogcms.service.impl;

import com.aditya_nk.blogcms.client.AIServiceClient;
import com.aditya_nk.blogcms.dto.ai.AIJobExcerptRequest;
import com.aditya_nk.blogcms.dto.ai.AIJobExpandRequest;
import com.aditya_nk.blogcms.dto.ai.AIJobImageRequest;
import com.aditya_nk.blogcms.dto.ai.AIJobOutlineRequest;
import com.aditya_nk.blogcms.dto.ai.AIJobPostRequest;
import com.aditya_nk.blogcms.dto.ai.AIJobResponse;
import com.aditya_nk.blogcms.dto.ai.AIJobStatusResponse;
import com.aditya_nk.blogcms.dto.ai.AIJobSuggestTagsRequest;
import com.aditya_nk.blogcms.dto.ai.ExcerptRequest;
import com.aditya_nk.blogcms.dto.ai.ExpandContentRequest;
import com.aditya_nk.blogcms.dto.ai.GenerateImageRequest;
import com.aditya_nk.blogcms.dto.ai.GenerateOutlineRequest;
import com.aditya_nk.blogcms.dto.ai.GeneratePostRequest;
import com.aditya_nk.blogcms.dto.ai.TagSuggestionRequest;
import com.aditya_nk.blogcms.entity.AIJob;
import com.aditya_nk.blogcms.entity.Category;
import com.aditya_nk.blogcms.exception.ResourceNotFoundException;
import com.aditya_nk.blogcms.mapper.AIJobStatusMapper;
import com.aditya_nk.blogcms.repository.AIJobRepository;
import com.aditya_nk.blogcms.repository.CategoryRepository;
import com.aditya_nk.blogcms.service.AIJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AIJobServiceImpl implements AIJobService {

    private static final String REDIS_KEY_PREFIX = "ai-job:";
    private static final String PENDING = "PENDING";

    private final AIServiceClient aiServiceClient;
    private final AIJobRepository aiJobRepository;
    private final CategoryRepository categoryRepository;
    private final StringRedisTemplate redisTemplate;
    private final AIJobStatusMapper aiJobStatusMapper;

    @Value("${ai.job.redis-ttl-seconds:3600}")
    private long redisTtlSeconds;

    @Override
    public AIJobResponse dispatchExcerptJob(ExcerptRequest request, Long userId) {
        String jobId = UUID.randomUUID().toString();

        redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + jobId, PENDING, Duration.ofSeconds(redisTtlSeconds));

        AIJobExcerptRequest dispatchRequest = AIJobExcerptRequest.builder()
                .jobId(jobId)
                .userId(userId)
                .content(request.getContent())
                .maxLength(request.getMaxLength())
                .build();

        aiServiceClient.dispatchExcerpt(dispatchRequest);
        log.info("Dispatched excerpt job {} for user {}", jobId, userId);

        return AIJobResponse.builder().jobId(jobId).status(PENDING).build();
    }

    @Override
    public AIJobResponse dispatchSuggestTagsJob(TagSuggestionRequest request, Long userId) {
        String jobId = UUID.randomUUID().toString();

        redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + jobId, PENDING, Duration.ofSeconds(redisTtlSeconds));

        AIJobSuggestTagsRequest dispatchRequest = AIJobSuggestTagsRequest.builder()
                .jobId(jobId)
                .userId(userId)
                .content(request.getContent())
                .maxTags(request.getMaxTags())
                .build();

        aiServiceClient.dispatchSuggestTags(dispatchRequest);
        log.info("Dispatched suggest-tags job {} for user {}", jobId, userId);

        return AIJobResponse.builder().jobId(jobId).status(PENDING).build();
    }

    @Override
    public AIJobResponse dispatchOutlineJob(GenerateOutlineRequest request, Long userId) {
        String jobId = UUID.randomUUID().toString();
        Category category = findCategory(request.getCategoryId());

        redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + jobId, PENDING, Duration.ofSeconds(redisTtlSeconds));

        AIJobOutlineRequest dispatchRequest = AIJobOutlineRequest.builder()
                .jobId(jobId)
                .userId(userId)
                .topic(request.getTopic())
                .categoryName(category.getName())
                .targetAudience(request.getTargetAudience())
                .additionalInstructions(request.getAdditionalInstructions())
                .build();

        aiServiceClient.dispatchOutline(dispatchRequest);
        log.info("Dispatched outline job {} for user {}", jobId, userId);

        return AIJobResponse.builder().jobId(jobId).status(PENDING).build();
    }

    @Override
    public AIJobResponse dispatchExpandJob(ExpandContentRequest request, Long userId) {
        String jobId = UUID.randomUUID().toString();
        Category category = findCategory(request.getCategoryId());

        redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + jobId, PENDING, Duration.ofSeconds(redisTtlSeconds));

        AIJobExpandRequest dispatchRequest = AIJobExpandRequest.builder()
                .jobId(jobId)
                .userId(userId)
                .content(request.getContent())
                .categoryName(category.getName())
                .focusAreas(request.getFocusAreas())
                .build();

        aiServiceClient.dispatchExpand(dispatchRequest);
        log.info("Dispatched expand job {} for user {}", jobId, userId);

        return AIJobResponse.builder().jobId(jobId).status(PENDING).build();
    }

    @Override
    public AIJobResponse dispatchPostJob(GeneratePostRequest request, Long userId) {
        String jobId = UUID.randomUUID().toString();
        Category category = findCategory(request.getCategoryId());

        redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + jobId, PENDING, Duration.ofSeconds(redisTtlSeconds));

        AIJobPostRequest dispatchRequest = AIJobPostRequest.builder()
                .jobId(jobId)
                .userId(userId)
                .topic(request.getTopic())
                .categoryName(category.getName())
                .outline(request.getOutline())
                .additionalInstructions(request.getAdditionalInstructions())
                .build();

        aiServiceClient.dispatchPost(dispatchRequest);
        log.info("Dispatched post job {} for user {}", jobId, userId);

        return AIJobResponse.builder().jobId(jobId).status(PENDING).build();
    }

    @Override
    public AIJobResponse dispatchImageJob(GenerateImageRequest request, Long userId) {
        String jobId = UUID.randomUUID().toString();
        Category category = findCategory(request.getCategoryId());

        redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + jobId, PENDING, Duration.ofSeconds(redisTtlSeconds));

        AIJobImageRequest dispatchRequest = AIJobImageRequest.builder()
                .jobId(jobId)
                .userId(userId)
                .title(request.getTitle())
                .categoryName(category.getName())
                .style(request.getStyle())
                .additionalContext(request.getAdditionalContext())
                .build();

        aiServiceClient.dispatchImage(dispatchRequest);
        log.info("Dispatched image job {} for user {}", jobId, userId);

        return AIJobResponse.builder().jobId(jobId).status(PENDING).build();
    }

    private Category findCategory(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category", "id", categoryId));
    }

    @Override
    public AIJobStatusResponse getJobStatus(String jobId) {
        String redisStatus = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + jobId);

        // Still running — Redis is authoritative and cheap, no DB read needed.
        if (PENDING.equals(redisStatus)) {
            return AIJobStatusResponse.builder().jobId(jobId).status(PENDING).build();
        }

        // Finished (per Redis) or Redis key already expired — either way the
        // durable payload only ever lives in ai_jobs, so read it from there.
        AIJob job = aiJobRepository.findById(UUID.fromString(jobId))
                .orElseThrow(() -> new ResourceNotFoundException("AI job", "id", jobId));

        return aiJobStatusMapper.toResponse(job);
    }
}
