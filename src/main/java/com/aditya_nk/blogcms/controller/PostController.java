package com.aditya_nk.blogcms.controller;

import com.aditya_nk.blogcms.dto.request.CreatePostRequest;
import com.aditya_nk.blogcms.dto.request.UpdatePostRequest;
import com.aditya_nk.blogcms.dto.response.PostResponse;
import com.aditya_nk.blogcms.entity.User;
import com.aditya_nk.blogcms.service.PostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for post operations.
 * Handles CRUD operations for blog posts.
 *
 * WARNING: This controller will demonstrate the N+1 query problem!
 *
 * When fetching lists of posts (getAllPosts, getPostsByUser, etc.),
 * the PostService triggers N+1 queries because:
 * - 1 query fetches posts
 * - N queries fetch each post's author (lazy loading)
 * - N queries fetch each post's category (lazy loading)
 * - N queries fetch each post's tags (lazy loading)
 *
 * To observe N+1 problem:
 * 1. Enable SQL logging in application.properties:
 *    spring.jpa.show-sql=true
 *    spring.jpa.properties.hibernate.format_sql=true
 *
 * 2. Add statistics logging:
 *    spring.jpa.properties.hibernate.generate_statistics=true
 *    logging.level.org.hibernate.stat=DEBUG
 *
 * 3. Call GET /api/v1/posts with 10 posts in database
 * 4. Observe 31 queries in console (1 + 10 + 10 + 10)
 *
 * This will be optimized in a future commit using JOIN FETCH.
 */
@RestController
@RequestMapping("/api/v1/posts")
@RequiredArgsConstructor
@Slf4j
public class PostController {

    private final PostService postService;

    /**
     * Create a new post.
     * Only authenticated users with AUTHOR or ADMIN role can create posts.
     *
     * @param request the post creation request
     * @param user the authenticated user (injected by Spring Security)
     * @return the created post response
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('AUTHOR', 'ADMIN')")
    public ResponseEntity<PostResponse> createPost(
            @Valid @RequestBody CreatePostRequest request,
            @AuthenticationPrincipal User user) {

        log.info("Creating post with title: '{}' by user: {}", request.getTitle(), user.getEmail());

        PostResponse response = postService.createPost(request, user.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get all published posts with pagination.
     * Public endpoint - no authentication required.
     *
     * WARNING: This endpoint demonstrates N+1 query problem!
     * Watch the console logs when calling this endpoint.
     *
     * @param page page number (0-indexed)
     * @param size page size (default 10)
     * @param sortBy field to sort by (default "publishedAt")
     * @param sortDir sort direction "asc" or "desc" (default "desc")
     * @return page of post responses
     */
    @GetMapping
    public ResponseEntity<Page<PostResponse>> getAllPosts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "publishedAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        log.info("Fetching posts - page: {}, size: {}, sortBy: {}, sortDir: {}",
                page, size, sortBy, sortDir);
        log.warn(">>> N+1 QUERY PROBLEM: Watch the SQL logs below! <<<");

        Page<PostResponse> posts = postService.getAllPosts(page, size, sortBy, sortDir);

        log.info("Returned {} posts (total: {})", posts.getNumberOfElements(), posts.getTotalElements());

        return ResponseEntity.ok(posts);
    }

    /**
     * Get a single post by ID.
     * Public endpoint - no authentication required.
     *
     * @param id the post ID
     * @return the post response
     */
    @GetMapping("/{id}")
    public ResponseEntity<PostResponse> getPostById(@PathVariable Long id) {
        log.info("Fetching post by ID: {}", id);

        PostResponse post = postService.getPostById(id);

        // Increment view count asynchronously (fire and forget)
        try {
            postService.incrementViewCount(id);
        } catch (Exception e) {
            log.warn("Failed to increment view count for post {}: {}", id, e.getMessage());
        }

        return ResponseEntity.ok(post);
    }

    /**
     * Get a single post by slug.
     * Public endpoint - no authentication required.
     * Used for SEO-friendly URLs.
     *
     * @param slug the post's URL-friendly slug
     * @return the post response
     */
    @GetMapping("/slug/{slug}")
    public ResponseEntity<PostResponse> getPostBySlug(@PathVariable String slug) {
        log.info("Fetching post by slug: {}", slug);

        PostResponse post = postService.getPostBySlug(slug);

        return ResponseEntity.ok(post);
    }

    /**
     * Update an existing post.
     * Only the post author or ADMIN can update.
     *
     * @param id the post ID
     * @param request the update request
     * @param user the authenticated user
     * @return the updated post response
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('AUTHOR', 'ADMIN')")
    public ResponseEntity<PostResponse> updatePost(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePostRequest request,
            @AuthenticationPrincipal User user) {

        log.info("Updating post {} by user: {}", id, user.getEmail());

        PostResponse response = postService.updatePost(id, request, user.getId());

        return ResponseEntity.ok(response);
    }

    /**
     * Delete a post (soft delete).
     * Only the post author or ADMIN can delete.
     *
     * @param id the post ID
     * @param user the authenticated user
     * @return 204 No Content on success
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('AUTHOR', 'ADMIN')")
    public ResponseEntity<Void> deletePost(
            @PathVariable Long id,
            @AuthenticationPrincipal User user) {

        log.info("Deleting post {} by user: {}", id, user.getEmail());

        postService.deletePost(id, user.getId());

        return ResponseEntity.noContent().build();
    }

    /**
     * Get posts by a specific user with pagination.
     * Public endpoint - no authentication required.
     *
     * WARNING: This endpoint demonstrates N+1 query problem!
     *
     * @param userId the author's user ID
     * @param page page number (0-indexed)
     * @param size page size (default 10)
     * @return page of post responses
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<Page<PostResponse>> getPostsByUser(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        log.info("Fetching posts for user {} - page: {}, size: {}", userId, page, size);

        Page<PostResponse> posts = postService.getPostsByUser(userId, page, size);

        return ResponseEntity.ok(posts);
    }

    /**
     * Search posts by title and content.
     * Public endpoint - no authentication required.
     *
     * WARNING: This endpoint demonstrates N+1 query problem!
     *
     * @param query the search query
     * @param page page number (0-indexed)
     * @param size page size (default 10)
     * @return page of matching post responses
     */
    @GetMapping("/search")
    public ResponseEntity<Page<PostResponse>> searchPosts(
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        log.info("Searching posts with query: '{}' - page: {}, size: {}", query, page, size);

        Page<PostResponse> posts = postService.searchPosts(query, page, size);

        return ResponseEntity.ok(posts);
    }

    /**
     * Get posts by category with pagination.
     * Public endpoint - no authentication required.
     *
     * WARNING: This endpoint demonstrates N+1 query problem!
     *
     * @param categoryId the category ID
     * @param page page number (0-indexed)
     * @param size page size (default 10)
     * @return page of post responses in the category
     */
    @GetMapping("/category/{categoryId}")
    public ResponseEntity<Page<PostResponse>> getPostsByCategory(
            @PathVariable Long categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        log.info("Fetching posts for category {} - page: {}, size: {}", categoryId, page, size);

        Page<PostResponse> posts = postService.getPostsByCategory(categoryId, page, size);

        return ResponseEntity.ok(posts);
    }

    /**
     * Get posts by tag.
     * Public endpoint - no authentication required.
     *
     * WARNING: This endpoint demonstrates N+1 query problem!
     *
     * @param tagId the tag ID
     * @return list of post responses with the tag
     */
    @GetMapping("/tag/{tagId}")
    public ResponseEntity<List<PostResponse>> getPostsByTag(@PathVariable Long tagId) {
        log.info("Fetching posts for tag {}", tagId);

        List<PostResponse> posts = postService.getPostsByTag(tagId);

        return ResponseEntity.ok(posts);
    }

    /**
     * Publish a draft post.
     * Only the post author or ADMIN can publish.
     *
     * @param id the post ID
     * @param user the authenticated user
     * @return the published post response
     */
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('AUTHOR', 'ADMIN')")
    public ResponseEntity<PostResponse> publishPost(
            @PathVariable Long id,
            @AuthenticationPrincipal User user) {

        log.info("Publishing post {} by user: {}", id, user.getEmail());

        PostResponse response = postService.publishPost(id, user.getId());

        return ResponseEntity.ok(response);
    }
}

