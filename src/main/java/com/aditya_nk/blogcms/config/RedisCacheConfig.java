package com.aditya_nk.blogcms.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis cache configuration for the Blog CMS.
 *
 * Responsibilities:
 *   - Enable Spring's caching abstraction (@EnableCaching)
 *   - Configure per-cache TTLs for "posts", "categories", and "tags"
 *   - Use Jackson JSON serialization so cached values are human-readable
 *     and not tied to JVM-specific binary format
 *   - Apply a sensible default TTL for any cache name not explicitly listed
 *
 * TTL values are read from application.properties so they can be tuned
 * per environment without recompiling.  Override examples:
 *   CACHE_TTL_POSTS=600  (10 min)
 *   CACHE_TTL_CATEGORIES=1800  (30 min)
 *
 * Redis password is intentionally left blank for local development. In production
 * set the REDIS_PASSWORD environment variable — Spring's ${...} binding picks it up
 * automatically via the spring.data.redis.password property.
 */
@Slf4j
@Configuration
@EnableCaching
@RequiredArgsConstructor
public class RedisCacheConfig {

    private final CacheTtlProperties ttl;

    /**
     * Builds the Jackson 3 serializer used for Redis cache values.
     *
     * GenericJacksonJsonRedisSerializer is the Spring Data Redis 4.x serializer that
     * uses the tools.jackson (Jackson 3) namespace, which is what Spring Boot 4
     * ships. It embeds the fully-qualified class name in each JSON document (default
     * typing) so that deserialization works without knowing the target type in advance.
     *
     * enableUnsafeDefaultTyping() is the equivalent of Jackson 2's
     * activateDefaultTyping(LaissezFaireSubTypeValidator, NON_FINAL, AS.PROPERTY).
     * It is "unsafe" only in the sense that it trusts all class names found in the
     * JSON; this is acceptable here because only our own application writes to this
     * Redis instance. Never use this on data from untrusted external sources.
     */
    private GenericJacksonJsonRedisSerializer jacksonSerializer() {
        return GenericJacksonJsonRedisSerializer.builder()
                .enableUnsafeDefaultTyping()
                .build();
    }

    /**
     * Builds the base RedisCacheConfiguration that applies to every cache
     * unless overridden by a per-cache entry in the manager.
     *
     * Key serializer:   StringRedisSerializer  → plain UTF-8 key string
     * Value serializer: GenericJacksonJsonRedisSerializer → JSON with type info
     * Null values:      disabled — caching null would hide missing entities
     */
    private RedisCacheConfiguration defaultCacheConfiguration() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(ttl.getDefaultTtl()))
                .disableCachingNullValues()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new StringRedisSerializer())
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(jacksonSerializer())
                );
    }

    /**
     * Declares and registers the RedisCacheManager bean.
     *
     * Spring's caching abstraction (used via @Cacheable / @CacheEvict on service
     * methods) routes cache lookups through this manager. Each named cache gets
     * its own TTL; any cache name not listed here uses defaultCacheConfiguration().
     *
     * @param connectionFactory injected by Spring Boot auto-configuration
     *                          from spring.data.redis.* properties
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        log.info("Initialising RedisCacheManager — posts={}s, categories={}s, tags={}s, default={}s",
                ttl.getPosts(), ttl.getCategories(), ttl.getTags(), ttl.getDefaultTtl());

        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        cacheConfigurations.put("posts",
                defaultCacheConfiguration().entryTtl(Duration.ofSeconds(ttl.getPosts())));

        cacheConfigurations.put("categories",
                defaultCacheConfiguration().entryTtl(Duration.ofSeconds(ttl.getCategories())));

        cacheConfigurations.put("tags",
                defaultCacheConfiguration().entryTtl(Duration.ofSeconds(ttl.getTags())));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultCacheConfiguration())
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }
}

