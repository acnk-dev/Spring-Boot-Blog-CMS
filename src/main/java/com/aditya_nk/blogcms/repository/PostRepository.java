package com.aditya_nk.blogcms.repository;

import com.aditya_nk.blogcms.entity.Post;
import com.aditya_nk.blogcms.enums.PostStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
     *
     * @param status the post status (DRAFT, PUBLISHED)
     * @param pageable pagination information
     * @return Page of posts matching criteria
     */
    Page<Post> findByStatusAndIsDeletedFalse(PostStatus status, Pageable pageable);

    /**
     * Find posts by author with pagination.
     * Excludes soft-deleted posts.
     * Used for author profile page showing their posts.
     *
     * @param userId the author's user ID
     * @param pageable pagination information
     * @return Page of posts by specified author
     */
    Page<Post> findByUserIdAndIsDeletedFalse(Long userId, Pageable pageable);

    /**
     * Find post by slug.
     * Excludes soft-deleted posts.
     * Used for URL routing (e.g., /posts/my-post-slug).
     *
     * @param slug the URL-friendly identifier
     * @return Optional containing post if found, empty otherwise
     */
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
     * Search posts by title or content.
     * Uses case-insensitive LIKE search.
     * Excludes soft-deleted posts.
     * Only returns published posts.
     *
     * @param searchTerm the term to search for in title and content
     * @param pageable pagination information
     * @return Page of posts matching search criteria
     */
    @Query("SELECT p FROM Post p WHERE p.isDeleted = false " +
            "AND p.status = 'PUBLISHED' " +
            "AND (LOWER(p.title) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
            "OR LOWER(p.content) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
    Page<Post> searchPublishedPosts(@Param("searchTerm") String searchTerm, Pageable pageable);

    /**
     * Find posts by category with status filter and pagination.
     * Excludes soft-deleted posts.
     * Used for category pages (e.g., /categories/technology).
     *
     * @param categoryId the category ID
     * @param status the post status
     * @param pageable pagination information
     * @return Page of posts in specified category
     */
    Page<Post> findByCategoryIdAndStatusAndIsDeletedFalse(
            Long categoryId, PostStatus status, Pageable pageable);

    /**
     * Find posts by tag with status filter.
     * Excludes soft-deleted posts.
     * Used for tag pages (e.g., /tags/java).
     *
     * @param tagId the tag ID
     * @param status the post status
     * @return List of posts with specified tag
     */
    List<Post> findByTagsIdAndStatusAndIsDeletedFalse(Long tagId, PostStatus status);

    /**
     * Find posts by tag with pagination.
     * Excludes soft-deleted posts.
     * Used for tag pages with pagination.
     *
     * @param tagId the tag ID
     * @param status the post status
     * @param pageable pagination information
     * @return Page of posts with specified tag
     */
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
     *
     * @param pageable pagination information
     * @return Page of active posts
     */
    Page<Post> findByIsDeletedFalse(Pageable pageable);

    /**
     * Find posts by author and status.
     * Excludes soft-deleted posts.
     * Used for author dashboard (e.g., "My Drafts", "My Published").
     *
     * @param userId the author's user ID
     * @param status the post status
     * @param pageable pagination information
     * @return Page of posts matching criteria
     */
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
}

