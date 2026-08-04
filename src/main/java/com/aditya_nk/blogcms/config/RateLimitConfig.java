package com.aditya_nk.blogcms.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

/**
 * Rate limiting configuration for the Blog CMS.
 *
 * <p>Uses Bucket4j's token-bucket algorithm backed by Redis so that limits are
 * enforced across all application instances (distributed / horizontally-scaled
 * deployments). Bucket state is stored as a small byte-array value in Redis under
 * the key {@code "rate_limit:<identity>"}, where identity is either
 * {@code "<username>:<role>"} for authenticated requests or
 * {@code "anon:<ip>"} for unauthenticated requests.</p>
 *
 * <h2>Per-role limits</h2>
 * <ul>
 *   <li>SUBSCRIBER — 100 requests / hour (typical read-only consumer)</li>
 *   <li>AUTHOR — 500 requests / hour (content creators, more API usage)</li>
 *   <li>ADMIN — effectively unlimited (Long.MAX_VALUE / 2 tokens / hour)</li>
 *   <li>Anonymous (unauthenticated) — same as SUBSCRIBER, keyed by IP</li>
 * </ul>
 *
 * <h2>Why Redis for state?</h2>
 * <p>A simple in-memory counter would reset on every pod restart and would allow
 * a user to bypass limits by hitting different pods in a load-balanced cluster.
 * Redis provides a single, consistent counter store shared across all pods.
 * Bucket4j's {@link LettuceBasedProxyManager} implements compare-and-swap (CAS)
 * semantics on top of Redis Lua scripts so counter updates are atomic.</p>
 */
@Slf4j
@Configuration
public class RateLimitConfig {

    // -----------------------------------------------------------------------
    // Bandwidth constants — one Bandwidth per role, reused by RateLimitFilter
    // -----------------------------------------------------------------------

    /**
     * SUBSCRIBER: 100 tokens, refilled fully every 60 minutes.
     * Each API request consumes 1 token.
     */
    public static final Bandwidth SUBSCRIBER_BANDWIDTH =
            Bandwidth.builder()
                    .capacity(100)
                    .refillGreedy(100, Duration.ofHours(1))
                    .build();

    /**
     * AUTHOR: 500 tokens per hour — content creators call more endpoints.
     */
    public static final Bandwidth AUTHOR_BANDWIDTH =
            Bandwidth.builder()
                    .capacity(500)
                    .refillGreedy(500, Duration.ofHours(1))
                    .build();

    /**
     * ADMIN: effectively unlimited.
     * {@code Long.MAX_VALUE / 2} avoids arithmetic overflow inside Bucket4j
     * when it computes remaining tokens.
     */
    public static final Bandwidth ADMIN_BANDWIDTH =
            Bandwidth.builder()
                    .capacity(Long.MAX_VALUE / 2)
                    .refillGreedy(Long.MAX_VALUE / 2, Duration.ofHours(1))
                    .build();

    // -----------------------------------------------------------------------
    // ProxyManager bean
    // -----------------------------------------------------------------------

    /**
     * Creates a {@link LettuceBasedProxyManager} that stores bucket state in Redis.
     *
     * <p>Spring Boot auto-configures a {@link LettuceConnectionFactory} when
     * {@code spring-boot-starter-data-redis} is on the classpath. We cast its
     * native client to {@link RedisClient} (safe for non-cluster deployments) and
     * hand it to Bucket4j. Bucket4j then manages its own connection pool to Redis
     * for atomic CAS operations.</p>
     *
     * <p>The {@code expiresAfterWrite} TTL is set to 2 hours — slightly longer than
     * the longest refill window (1 hour) — so Redis automatically reclaims memory
     * for inactive users without manual cleanup.</p>
     *
     * @param lettuceConnectionFactory Spring Data Redis Lettuce factory
     * @return a thread-safe ProxyManager backed by Redis
     */
    /**
     * Creates a {@link LettuceBasedProxyManager} keyed by {@code byte[]}.
     *
     * <p>{@code builderFor(RedisClient)} is the simplest overload when using a
     * non-cluster Redis deployment; it produces a {@code ProxyManager<byte[]>}
     * because the low-level Redis protocol works with raw bytes. The filter layer
     * converts human-readable String keys to UTF-8 bytes before calling
     * {@code proxyManager.builder().build(keyBytes, config)}.</p>
     *
     * <p>The {@code fixedTimeToLive} expiration tells Redis to set a TTL of 2 hours
     * on every bucket key. This automatically reclaims memory for users who stop
     * making requests without requiring an explicit delete operation.</p>
     *
     * @param lettuceConnectionFactory Spring Data Redis Lettuce factory
     * @return a thread-safe ProxyManager backed by Redis
     */
    @Bean
    public LettuceBasedProxyManager<byte[]> rateLimitProxyManager(
            LettuceConnectionFactory lettuceConnectionFactory) {

        // getNativeClient() returns AbstractRedisClient; cast is safe when not
        // using Redis Cluster (LettuceConnectionFactory wraps a plain RedisClient).
        RedisClient redisClient = (RedisClient) lettuceConnectionFactory.getNativeClient();

        log.info("Initialising Bucket4j LettuceBasedProxyManager for distributed rate limiting");

        return LettuceBasedProxyManager.builderFor(redisClient)
                .withExpirationStrategy(ExpirationAfterWriteStrategy.fixedTimeToLive(Duration.ofHours(2)))
                .build();
    }
}

