package com.aditya_nk.blogcms.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Admin-only REST controller for monitoring and managing the Redis cache.
 *
 * All endpoints are protected at the class level with @PreAuthorize("hasRole('ADMIN')").
 * This means every method in this controller requires an authenticated user whose
 * granted authority includes ROLE_ADMIN (Spring prepends "ROLE_" automatically when
 * using hasRole()).
 *
 * No service layer is used here deliberately: CacheManager and StringRedisTemplate are
 * infrastructure beans, not business logic. Routing through a service interface would add
 * indirection without benefit for operational endpoints like these.
 *
 * IMPORTANT — production note on StringRedisTemplate.keys():
 *   keys("pattern") is a blocking O(N) Redis command that scans the entire keyspace.
 *   On a large production Redis instance this can cause latency spikes. For large
 *   deployments, replace with SCAN-based iteration. For this teaching project the
 *   simpler KEYS command is acceptable.
 */
@RestController
@RequestMapping("/api/v1/admin/cache")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class CacheAdminController {

    private final CacheManager cacheManager;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * GET /api/v1/admin/cache/stats
     *
     * Returns the number of live entries in each named cache.
     *
     * Redis does not expose hit/miss ratios through the standard CacheManager API;
     * the only observable metric available without enabling Redis INFO stats is the
     * current key count per cache namespace. Each key is prefixed with "cacheName::"
     * by Spring's RedisCacheWriter, so we use KEYS("cacheName::*") to count them.
     *
     * Response shape:
     * {
     *   "caches": {
     *     "posts":      { "entryCount": 12 },
     *     "categories": { "entryCount": 3  },
     *     "tags":       { "entryCount": 7  }
     *   }
     * }
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getCacheStats() {
        log.info("Admin requested cache stats");

        Map<String, Object> cacheStats = new LinkedHashMap<>();

        for (String cacheName : cacheManager.getCacheNames()) {
            Set<String> keys = stringRedisTemplate.keys(cacheName + "::*");
            int entryCount = (keys != null) ? keys.size() : 0;

            Map<String, Object> cacheInfo = new LinkedHashMap<>();
            cacheInfo.put("entryCount", entryCount);

            cacheStats.put(cacheName, cacheInfo);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("caches", cacheStats);

        log.info("Cache stats: {}", cacheStats);
        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /api/v1/admin/cache/clear/{cacheName}
     *
     * Evicts all entries from a single named cache.
     *
     * Returns 404 if the cache name is not registered with the CacheManager.
     * Returns 200 with a success message when the cache is cleared.
     *
     * cache.clear() delegates to RedisCacheWriter which issues a Redis DEL/SCAN
     * to remove all keys matching "cacheName::*".
     */
    @DeleteMapping("/clear/{cacheName}")
    public ResponseEntity<Map<String, String>> clearCache(@PathVariable String cacheName) {
        log.info("Admin requested eviction of cache '{}'", cacheName);

        org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);

        if (cache == null) {
            log.warn("Cache '{}' not found in CacheManager", cacheName);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("message", "Cache '" + cacheName + "' not found. Known caches: " + cacheManager.getCacheNames());
            return ResponseEntity.status(404).body(errorResponse);
        }

        cache.clear();
        log.info("Cache '{}' cleared successfully", cacheName);

        Map<String, String> response = new HashMap<>();
        response.put("message", "Cache '" + cacheName + "' cleared successfully");
        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /api/v1/admin/cache/clear-all
     *
     * Evicts all entries from every named cache registered with the CacheManager.
     *
     * Response shape:
     * {
     *   "message": "3 cache(s) cleared successfully",
     *   "clearedCaches": "posts, categories, tags"
     * }
     */
    @DeleteMapping("/clear-all")
    public ResponseEntity<Map<String, String>> clearAllCaches() {
        log.info("Admin requested eviction of all caches");

        int count = 0;
        for (String cacheName : cacheManager.getCacheNames()) {
            org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
                log.info("Cleared cache '{}'", cacheName);
                count++;
            }
        }

        Map<String, String> response = new HashMap<>();
        response.put("message", count + " cache(s) cleared successfully");
        response.put("clearedCaches", String.join(", ", cacheManager.getCacheNames()));
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/admin/cache/keys/{cacheName}
     *
     * Returns the full set of Redis key strings for a given cache namespace.
     *
     * Keys are in the format "cacheName::keyValue", for example:
     *   posts::1
     *   posts::SimpleKey [0, 10, publishedAt, desc]
     *
     * Returns 404 if the cache name is not registered.
     * Returns the raw Set<String> of key names on success.
     *
     * Note: StringRedisTemplate.keys() may return null if Redis returns an empty result;
     * this is handled by returning an empty set.
     */
    @GetMapping("/keys/{cacheName}")
    public ResponseEntity<Set<String>> getCacheKeys(@PathVariable String cacheName) {
        log.info("Admin requested key listing for cache '{}'", cacheName);

        // Verify the cache is known to the CacheManager before querying Redis
        org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            log.warn("Cache '{}' not found in CacheManager", cacheName);
            return ResponseEntity.status(404).build();
        }

        Set<String> keys = stringRedisTemplate.keys(cacheName + "::*");
        if (keys == null) {
            keys = Set.of();
        }

        log.info("Found {} key(s) in cache '{}'", keys.size(), cacheName);
        return ResponseEntity.ok(keys);
    }
}

