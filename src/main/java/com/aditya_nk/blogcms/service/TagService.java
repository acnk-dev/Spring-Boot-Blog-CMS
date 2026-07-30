package com.aditya_nk.blogcms.service;

import com.aditya_nk.blogcms.dto.request.CreateTagRequest;
import com.aditya_nk.blogcms.dto.response.TagResponse;

import java.util.List;
import java.util.Set;

/**
 * Service interface for Tag entity operations.
 * Defines the contract for tag management business logic.
 */
public interface TagService {

    /**
     * Create a new tag.
     * Automatically generates a slug from the name.
     *
     * @param request the tag creation request
     * @return the created tag response
     */
    TagResponse createTag(CreateTagRequest request);

    /**
     * Get all tags ordered by name.
     *
     * @return list of all tags
     */
    List<TagResponse> getAllTags();

    /**
     * Get a tag by its ID.
     *
     * @param id the tag ID
     * @return the tag response
     */
    TagResponse getTagById(Long id);

    /**
     * Get a tag by its slug.
     * Used for URL routing.
     *
     * @param slug the tag's URL-friendly slug
     * @return the tag response
     */
    TagResponse getTagBySlug(String slug);

    /**
     * Delete a tag.
     * Removes the tag from all posts that use it.
     *
     * @param id the tag ID
     */
    void deleteTag(Long id);

    /**
     * Search tags by name.
     *
     * @param searchTerm the search term
     * @return list of matching tags
     */
    List<TagResponse> searchTags(String searchTerm);

    /**
     * Find tags by name prefix.
     * Used for autocomplete functionality.
     *
     * @param prefix the name prefix
     * @return list of matching tags
     */
    List<TagResponse> findTagsByPrefix(String prefix);

    /**
     * Get or create tags by names.
     * Creates new tags for names that don't exist.
     * Used when saving posts with tag names instead of IDs.
     *
     * @param tagNames set of tag names
     * @return list of tag responses (existing + newly created)
     */
    List<TagResponse> getOrCreateTags(Set<String> tagNames);

    /**
     * Get popular tags based on post count.
     *
     * @param limit maximum number of tags to return
     * @return list of popular tags
     */
    List<TagResponse> getPopularTags(int limit);

    /**
     * Check if a tag exists with the given name.
     *
     * @param name the tag name
     * @return true if exists, false otherwise
     */
    boolean existsByName(String name);
}

