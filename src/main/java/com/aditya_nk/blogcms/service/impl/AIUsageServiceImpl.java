package com.aditya_nk.blogcms.service.impl;

import com.aditya_nk.blogcms.dto.ai.AIUsageResponse;
import com.aditya_nk.blogcms.enums.Role;
import com.aditya_nk.blogcms.exception.AIUsageLimitException;
import com.aditya_nk.blogcms.service.AIUsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AIUsageServiceImpl implements AIUsageService {

    private static final Map<Role, Integer> LIMITS = Map.of(
            Role.SUBSCRIBER, 5,
            Role.AUTHOR, 50,
            Role.ADMIN, Integer.MAX_VALUE
    );

    private final StringRedisTemplate redisTemplate;

    @Override
    public void checkAndIncrementUsage(Long userId, Role role) {
        if (role == Role.ADMIN) {
            return;
        }

        String key = usageKey(userId);
        int limit = LIMITS.getOrDefault(role, 5);

        String raw = redisTemplate.opsForValue().get(key);
        int current = raw != null ? Integer.parseInt(raw) : 0;

        if (current >= limit) {
            throw new AIUsageLimitException(String.format(
                    "Monthly AI usage limit reached (%d/%d). Upgrade your plan for more generations.",
                    current, limit));
        }

        Long newCount = redisTemplate.opsForValue().increment(key);

        // Set TTL on the first write so the key auto-expires at month end
        if (newCount != null && newCount == 1) {
            redisTemplate.expire(key, ttlUntilMonthEnd());
        }

        log.info("AI usage incremented for user {}: {}/{}", userId, newCount, limit);
    }

    @Override
    public AIUsageResponse getUsage(Long userId, Role role) {
        String key = usageKey(userId);
        String raw = redisTemplate.opsForValue().get(key);
        int used = raw != null ? Integer.parseInt(raw) : 0;
        int limit = LIMITS.getOrDefault(role, 5);

        return AIUsageResponse.builder()
                .used(used)
                .limit(role == Role.ADMIN ? -1 : limit)
                .remaining(role == Role.ADMIN ? -1 : Math.max(0, limit - used))
                .resetsAt(endOfMonth().toString())
                .build();
    }

    // Redis key: ai:usage:{userId}:{yyyy-MM}  e.g. ai:usage:42:2026-06
    private String usageKey(Long userId) {
        return "ai:usage:" + userId + ":" + YearMonth.now();
    }

    private Duration ttlUntilMonthEnd() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        ZonedDateTime end = endOfMonth();
        return Duration.between(now, end);
    }

    private ZonedDateTime endOfMonth() {
        return YearMonth.now()
                .atEndOfMonth()
                .atTime(23, 59, 59)
                .atZone(ZoneId.systemDefault());
    }
}

