package com.aditya_nk.blogcms.repository;

import com.aditya_nk.blogcms.entity.Post;
import com.aditya_nk.blogcms.enums.PostStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Post entity.
 * Provides CRUD operations and custom query methods for post management.
 *
 * Spring Data JPA automatically implements these methods at runtime.
 * All queries exclude soft-deleted posts unless explicitly stated.
 */
@Repository
public interface PostRepository extends JpaRepository<Post, Long> {

    /**
     * Find posts by status with pagination.
     * Excludes soft-deleted posts.
     * Used for listing published posts on homepage/blog page.
     * Uses @EntityGraph to eagerly fetch user, category, and tags in a single query.
     *
     * @param status the post status (DRAFT, PUBLISHED)
     * @param pageable pagination information
     * @return Page of posts matching criteria
     */
    @EntityGraph(attributePaths = {"user", "category", "tags"})
    Page<Post> findByStatusAndIsDeletedFalse(PostStatus status, Pageable pageable);

    /**
     * Find posts by author with pagination.
     * Excludes soft-deleted posts.
     * Used for author profile page showing their posts.
     * Uses @EntityGraph to eagerly fetch user, category, and tags in a single query.
     *
     * @param userId the author's user ID
     * @param pageable pagination information
     * @return Page of posts by specified author
     */
    @EntityGraph(attributePaths = {"user", "category", "tags"})
    Page<Post> findByUserIdAndIsDeletedFalse(Long userId, Pageable pageable);

    /**
     * Find post by slug.
     * Excludes soft-deleted posts.
     * Used for URL routing (e.g., /posts/my-post-slug).
     * Uses @EntityGraph to eagerly fetch user, category, and tags in a single query.
     *
     * @param slug the URL-friendly identifier
     * @return Optional containing post if found, empty otherwise
     */
    @EntityGraph(attributePaths = {"user", "category", "tags"})
    Optional<Post> findBySlugAndIsDeletedFalse(String slug);

    /**
     * Find post by slug regardless of deletion status.
     * Used for admin operations and redirects.
     *
     * @param slug the URL-friendly identifier
     * @return Optional containing post if found, empty otherwise
     */
    Optional<Post> findBySlug(String slug);

    /**
     * Check if slug already exists.
     * Used to ensure unique slugs when creating posts.
     *
     * @param slug the slug to check
     * @return true if slug exists, false otherwise
     */
    boolean existsBySlug(String slug);

    /**
     * Step 1 of full-text search: find matching post IDs using the GIN index.
     *
     * Uses PostgreSQL's @@ operator with plainto_tsquery to match against the
     * pre-computed search_vector column. Filters for published, non-deleted posts.
     *
     * Returns a Page of IDs so that Spring Data can compute total count and
     * provide pagination metadata without loading any entity data.
     *
     * plainto_tsquery('english', :query) converts a plain phrase like
     * "spring boot" into the tsquery 'spring' & 'boot' automatically.
     */
    @Query(
            value = "SELECT p.id FROM posts p " +
                    "WHERE p.is_deleted = false " +
                    "AND p.status = 'PUBLISHED' " +
                    "AND p.search_vector @@ plainto_tsquery('english', :query) " +
                    "ORDER BY p.published_at DESC",
            countQuery = "SELECT COUNT(p.id) FROM posts p " +
                    "WHERE p.is_deleted = false " +
                    "AND p.status = 'PUBLISHED' " +
                    "AND p.search_vector @@ plainto_tsquery('english', :query)",
            nativeQuery = true
    )
    Page<Long> findPublishedPostIdsByFullTextSearch(@Param("query") String query, Pageable pageable);

    /**
     * Step 2 of full-text search: load full Post entities with associations
     * for a specific set of IDs found in Step 1.
     *
     * Uses @EntityGraph to fetch user, category, and tags in a single
     * JOIN FETCH query — exactly as all other list-returning methods do.
     * This prevents N+1 queries when the mapper accesses relationships.
     */
    @EntityGraph(attributePaths = {"user", "category", "tags"})
    @Query("SELECT p FROM Post p WHERE p.id IN :ids")
    List<Post> findByIdIn(@Param("ids") List<Long> ids);

    /**
     * Find posts by category with status filter and pagination.
     * Excludes soft-deleted posts.
     * Used for category pages (e.g., /categories/technology).
     * Uses @EntityGraph to eagerly fetch user, category, and tags in a single query.
     *
     * @param categoryId the category ID
     * @param status the post status
     * @param pageable pagination information
     * @return Page of posts in specified category
     */
    @EntityGraph(attributePaths = {"user", "category", "tags"})
    Page<Post> findByCategoryIdAndStatusAndIsDeletedFalse(
            Long categoryId, PostStatus status, Pageable pageable);

    /**
     * Find posts by tag with status filter.
     * Excludes soft-deleted posts.
     * Used for tag pages (e.g., /tags/java).
     * Uses @EntityGraph to eagerly fetch user, category, and tags in a single query.
     *
     * @param tagId the tag ID
     * @param status the post status
     * @return List of posts with specified tag
     */
    @EntityGraph(attributePaths = {"user", "category", "tags"})
    List<Post> findByTagsIdAndStatusAndIsDeletedFalse(Long tagId, PostStatus status);

    /**
     * Find posts by tag with pagination.
     * Excludes soft-deleted posts.
     * Used for tag pages with pagination.
     * Uses @EntityGraph to eagerly fetch user, category, and tags in a single query.
     *
     * @param tagId the tag ID
     * @param status the post status
     * @param pageable pagination information
     * @return Page of posts with specified tag
     */
    @EntityGraph(attributePaths = {"user", "category", "tags"})
    Page<Post> findByTagsIdAndStatusAndIsDeletedFalse(
            Long tagId, PostStatus status, Pageable pageable);

    /**
     * Count posts in a category.
     * Includes all posts regardless of status or deletion.
     * Used for admin statistics.
     *
     * @param categoryId the category ID
     * @return count of posts in category
     */
    long countByCategoryId(Long categoryId);

    /**
     * Count published posts in a category.
     * Excludes soft-deleted posts.
     * Used for displaying post count on category pages.
     *
     * @param categoryId the category ID
     * @return count of published posts in category
     */
    long countByCategoryIdAndStatusAndIsDeletedFalse(Long categoryId, PostStatus status);

    /**
     * Count posts by author.
     * Excludes soft-deleted posts.
     *
     * @param userId the author's user ID
     * @return count of posts by author
     */
    long countByUserIdAndIsDeletedFalse(Long userId);

    /**
     * Find all active posts (not soft-deleted).
     * Includes both DRAFT and PUBLISHED.
     * Used for admin listing.
     * Uses @EntityGraph to eagerly fetch user, category, and tags in a single query.
     *
     * @param pageable pagination information
     * @return Page of active posts
     */
    @EntityGraph(attributePaths = {"user", "category", "tags"})
    Page<Post> findByIsDeletedFalse(Pageable pageable);

    /**
     * Find posts by author and status.
     * Excludes soft-deleted posts.
     * Used for author dashboard (e.g., "My Drafts", "My Published").
     * Uses @EntityGraph to eagerly fetch user, category, and tags in a single query.
     *
     * @param userId the author's user ID
     * @param status the post status
     * @param pageable pagination information
     * @return Page of posts matching criteria
     */
    @EntityGraph(attributePaths = {"user", "category", "tags"})
    Page<Post> findByUserIdAndStatusAndIsDeletedFalse(
            Long userId, PostStatus status, Pageable pageable);

    /**
     * Increment view count for a post.
     * Atomic update to prevent race conditions.
     *
     * @param postId the post ID
     */
    @Modifying
    @Query("UPDATE Post p SET p.viewCount = p.viewCount + 1 WHERE p.id = :postId")
    void incrementViewCount(@Param("postId") Long postId);

    /**
     * Increment like count for a post.
     * Atomic update to prevent race conditions.
     *
     * @param postId the post ID
     */
    @Modifying
    @Query("UPDATE Post p SET p.likeCount = p.likeCount + 1 WHERE p.id = :postId")
    void incrementLikeCount(@Param("postId") Long postId);

    /**
     * Decrement like count for a post.
     * Atomic update to prevent race conditions.
     * Ensures count doesn't go below 0.
     *
     * @param postId the post ID
     */
    @Modifying
    @Query("UPDATE Post p SET p.likeCount = CASE WHEN p.likeCount > 0 THEN p.likeCount - 1 ELSE 0 END WHERE p.id = :postId")
    void decrementLikeCount(@Param("postId") Long postId);

    // ── Cleanup / retention queries ──────────────────────────────────────────

    /**
     * Find soft-deleted posts whose updatedAt timestamp is older than the cutoff.
     * Used by the cleanup scheduler to identify candidates for hard-deletion.
     *
     * @param cutoff anything updated before this datetime is eligible
     * @return list of posts past the retention window
     */
    List<Post> findByIsDeletedTrueAndUpdatedAtBefore(LocalDateTime cutoff);

    /**
     * Hard-delete posts that have been soft-deleted AND are past the retention cutoff.
     * Uses a JPQL DELETE statement — avoids loading entities into memory.
     *
     * @param cutoff datetime threshold; posts with updatedAt before this are removed
     * @return number of rows deleted
     */
    @Modifying
    @Query("DELETE FROM Post p WHERE p.isDeleted = true AND p.updatedAt < :cutoff")
    int hardDeleteByIsDeletedTrueAndUpdatedAtBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Count posts that are currently soft-deleted.
     * Used by the stats job to report on accumulated soft-deleted records.
     *
     * @return count of soft-deleted posts
     */
    long countByIsDeletedTrue();

    @Modifying
    @Query("UPDATE Post p SET p.updatedAt = :updatedAt WHERE p.id = :id")
    void backdateUpdatedAt(@Param("id") Long id, @Param("updatedAt") LocalDateTime updatedAt);
}

