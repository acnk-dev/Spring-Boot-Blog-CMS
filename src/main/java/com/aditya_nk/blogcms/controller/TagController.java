package com.aditya_nk.blogcms.controller;

import com.aditya_nk.blogcms.dto.request.CreateTagRequest;
import com.aditya_nk.blogcms.dto.response.TagResponse;
import com.aditya_nk.blogcms.service.TagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for tag operations.
 * Tags are flat, multi-value labels attached to blog posts.
 *
 * Access control:
 * - Write operations (create/delete): ADMIN only
 * - Read operations (get/list): PUBLIC (no authentication required)
 *
 * Tags intentionally have no update endpoint.
 * If a tag name needs changing, delete the old tag and create a new one.
 * This keeps tag history clean and avoids silent slug drift on published posts.
 */
@RestController
@RequestMapping("/api/v1/tags")
@RequiredArgsConstructor
@Slf4j
public class TagController {

    private final TagService tagService;

    /**
     * Create a new tag.
     * Only ADMIN can define new tags.
     * A slug is automatically generated from the tag name.
     *
     * @param request the tag creation request (name only)
     * @return the created tag with generated slug, HTTP 201
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TagResponse> createTag(
            @Valid @RequestBody CreateTagRequest request) {

        log.info("Creating tag with name: '{}'", request.getName());

        TagResponse response = tagService.createTag(request);

        log.info("Tag created with ID: {} and slug: '{}'", response.getId(), response.getSlug());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get all tags ordered by name.
     * Public endpoint - no authentication required.
     * Used to populate tag clouds and filter UIs.
     *
     * @return list of all tags
     */
    @GetMapping
    public ResponseEntity<List<TagResponse>> getAllTags() {
        log.info("Fetching all tags");

        List<TagResponse> tags = tagService.getAllTags();

        log.info("Returned {} tags", tags.size());

        return ResponseEntity.ok(tags);
    }

    /**
     * Get a single tag by ID.
     * Public endpoint - no authentication required.
     *
     * @param id the tag ID
     * @return the tag response
     */
    @GetMapping("/{id}")
    public ResponseEntity<TagResponse> getTagById(@PathVariable Long id) {
        log.info("Fetching tag by ID: {}", id);

        TagResponse tag = tagService.getTagById(id);

        return ResponseEntity.ok(tag);
    }

    /**
     * Delete a tag.
     * Only ADMIN can remove tags.
     * The tag is automatically removed from all posts that reference it
     * via the post_tags junction table (cascade handled by JPA).
     *
     * @param id the tag ID
     * @return 204 No Content on success
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteTag(@PathVariable Long id) {
        log.info("Deleting tag ID: {}", id);

        tagService.deleteTag(id);

        log.info("Tag ID: {} deleted successfully", id);

        return ResponseEntity.noContent().build();
    }
}

