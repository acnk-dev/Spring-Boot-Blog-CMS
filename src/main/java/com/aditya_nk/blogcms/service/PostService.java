package com.aditya_nk.blogcms.service;

import com.aditya_nk.blogcms.dto.request.CreatePostRequest;
import com.aditya_nk.blogcms.dto.request.UpdatePostRequest;
import com.aditya_nk.blogcms.dto.response.PostResponse;
import com.aditya_nk.blogcms.dto.response.RestPage;
import org.springframework.data.domain.Page;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Service interface for Post entity operations.
 * Defines the contract for post management business logic.
 *
 * WARNING: The basic implementation (PostServiceImpl) has N+1 query problems
 * that will be addressed in a future optimization commit.
 */
public interface PostService {

    /**
     * Create a new post.
     *
     * @param request the post creation request
     * @param userId the ID of the user creating the post
     * @return the created post response
     */
    PostResponse createPost(CreatePostRequest request, Long userId);

    /**
     * Update an existing post.
     * Only the author can update their own post.
     *
     * @param postId the ID of the post to update
     * @param request the update request with changed fields
     * @param userId the ID of the user making the update
     * @return the updated post response
     */
    PostResponse updatePost(Long postId, UpdatePostRequest request, Long userId);

    /**
     * Soft delete a post.
     * Only the author can delete their own post.
     *
     * @param postId the ID of the post to delete
     * @param userId the ID of the user requesting deletion
     */
    void deletePost(Long postId, Long userId);

    /**
     * Get a post by its ID.
     *
     * @param postId the post ID
     * @return the post response
     */
    PostResponse getPostById(Long postId);

    /**
     * Get a post by its slug.
     * Used for URL routing (e.g., /posts/my-post-slug).
     *
     * @param slug the post's URL-friendly slug
     * @return the post response
     */
    PostResponse getPostBySlug(String slug);

    /**
     * Get all published posts with pagination.
     *
     * WARNING: This method has N+1 query problem!
     *
     * @param page the page number (0-indexed)
     * @param size the page size
     * @param sortBy the field to sort by
     * @param sortDir the sort direction ("asc" or "desc")
     * @return page of post responses
     */
    RestPage<PostResponse> getAllPosts(int page, int size, String sortBy, String sortDir);

    /**
     * Get posts by a specific user with pagination.
     *
     * @param userId the author's user ID
     * @param page the page number (0-indexed)
     * @param size the page size
     * @return page of post responses
     */
    Page<PostResponse> getPostsByUser(Long userId, int page, int size);

    /**
     * Search posts by title and content.
     *
     * @param query the search query
     * @param page the page number (0-indexed)
     * @param size the page size
     * @return page of matching post responses
     */
    Page<PostResponse> searchPosts(String query, int page, int size);

    /**
     * Get posts by category with pagination.
     *
     * @param categoryId the category ID
     * @param page the page number (0-indexed)
     * @param size the page size
     * @return page of post responses in the category
     */
    RestPage<PostResponse> getPostsByCategory(Long categoryId, int page, int size);

    /**
     * Get posts by tag.
     *
     * @param tagId the tag ID
     * @return list of post responses with the tag
     */
    List<PostResponse> getPostsByTag(Long tagId);

    /**
     * Increment the view count for a post.
     *
     * @param postId the post ID
     */
    void incrementViewCount(Long postId);

    /**
     * Publish a draft post.
     * Sets status to PUBLISHED and publishedAt to current time.
     *
     * @param postId the post ID
     * @param userId the user ID (must be the author)
     * @return the published post response
     */
    PostResponse publishPost(Long postId, Long userId);

    /**
     * Upload or replace the featured image for a post.
     * Only the post owner or an ADMIN can perform this operation.
     * If the post already has a featured image, the old file is deleted first.
     *
     * @param postId the ID of the post to update
     * @param file   the new featured image file
     * @param userId the ID of the requesting user
     * @return the updated post response with the new featuredImageUrl
     */
    PostResponse uploadFeaturedImage(Long postId, MultipartFile file, Long userId);
}
