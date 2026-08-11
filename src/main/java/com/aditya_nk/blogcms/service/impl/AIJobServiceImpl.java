package com.aditya_nk.blogcms.service.impl;

import com.aditya_nk.blogcms.client.AIServiceClient;
import com.aditya_nk.blogcms.dto.ai.AIJobExcerptRequest;
import com.aditya_nk.blogcms.dto.ai.AIJobResponse;
import com.aditya_nk.blogcms.dto.ai.AIJobStatusResponse;
import com.aditya_nk.blogcms.dto.ai.ExcerptRequest;
import com.aditya_nk.blogcms.entity.AIJob;
import com.aditya_nk.blogcms.exception.ResourceNotFoundException;
import com.aditya_nk.blogcms.repository.AIJobRepository;
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
    private final StringRedisTemplate redisTemplate;

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

        return AIJobStatusResponse.builder()
                .jobId(jobId)
                .status(job.getStatus())
                .result(job.getResult())
                .error(job.getError())
                .build();
    }
}
