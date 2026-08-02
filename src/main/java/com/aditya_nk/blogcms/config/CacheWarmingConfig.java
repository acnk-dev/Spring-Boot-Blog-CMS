package com.aditya_nk.blogcms.config;

import com.aditya_nk.blogcms.service.CategoryService;
import com.aditya_nk.blogcms.service.PostService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Pre-warms the Redis cache after every application startup.
 *
 * Problem: After a server restart the Redis cache is completely empty. The first
 * requests for popular data (post listings, category lists) hit PostgreSQL and
 * are slow. This class solves the cold-cache problem by eagerly invoking the
 * service read methods that carry @Cacheable annotations. Those methods populate
 * Redis as a side-effect of their normal execution path, so subsequent requests
 * are served from cache from the very first second the application is ready.
 *
 * Design choices:
 *  - ApplicationRunner rather than CommandLineRunner: both work; ApplicationRunner
 *    receives the full ApplicationArguments object which is more extensible.
 *  - Each call is wrapped in its own try/catch so that a Redis outage or an
 *    empty database at startup cannot prevent the application from starting.
 *  - Only service interfaces are injected — never repositories or impls directly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheWarmingConfig implements ApplicationRunner {

    private final PostService postService;
    private final CategoryService categoryService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Cache warming started — pre-loading popular data into Redis");

        warmPostsCache();
        warmCategoriesCache();

        log.info("Cache warming completed");
    }

    private void warmPostsCache() {
        try {
            postService.getAllPosts(0, 20, "createdAt", "desc");
            log.info("Cache warming: first page of posts loaded (page=0, size=20)");
        } catch (Exception ex) {
            log.warn("Cache warming: failed to pre-load posts — {}", ex.getMessage());
        }
    }

    private void warmCategoriesCache() {
        try {
            categoryService.getAllCategories();
            log.info("Cache warming: all categories loaded");
        } catch (Exception ex) {
            log.warn("Cache warming: failed to pre-load categories — {}", ex.getMessage());
        }
    }
}

