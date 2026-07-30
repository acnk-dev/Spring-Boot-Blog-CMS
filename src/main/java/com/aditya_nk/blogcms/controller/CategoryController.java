package com.aditya_nk.blogcms.controller;

import com.aditya_nk.blogcms.dto.request.CreateCategoryRequest;
import com.aditya_nk.blogcms.dto.response.CategoryResponse;
import com.aditya_nk.blogcms.service.CategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for category operations.
 * Categories organize blog posts into logical groups.
 *
 * Access control:
 * - Write operations (create/update/delete): ADMIN only
 * - Read operations (get/list): PUBLIC (no authentication required)
 *
 * This asymmetry reflects the real-world use case:
 * - Readers browse categories without logging in
 * - Only admins define the content taxonomy
 */
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
@Slf4j
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * Create a new category.
     * Only ADMIN can define new content categories.
     *
     * @param request the category creation request (name, description)
     * @return the created category with generated slug, HTTP 201
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CategoryResponse> createCategory(
            @Valid @RequestBody CreateCategoryRequest request) {

        log.info("Creating category with name: '{}'", request.getName());

        CategoryResponse response = categoryService.createCategory(request);

        log.info("Category created with ID: {} and slug: '{}'", response.getId(), response.getSlug());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get all categories ordered by name.
     * Public endpoint - no authentication required.
     *
     * @return list of all categories
     */
    @GetMapping
    public ResponseEntity<List<CategoryResponse>> getAllCategories() {
        log.info("Fetching all categories");

        List<CategoryResponse> categories = categoryService.getAllCategories();

        log.info("Returned {} categories", categories.size());

        return ResponseEntity.ok(categories);
    }

    /**
     * Get a single category by ID.
     * Public endpoint - no authentication required.
     *
     * @param id the category ID
     * @return the category response
     */
    @GetMapping("/{id}")
    public ResponseEntity<CategoryResponse> getCategoryById(@PathVariable Long id) {
        log.info("Fetching category by ID: {}", id);

        CategoryResponse category = categoryService.getCategoryById(id);

        return ResponseEntity.ok(category);
    }

    /**
     * Get a single category by slug.
     * Public endpoint - no authentication required.
     * Used for SEO-friendly URLs (e.g., /categories/spring-boot).
     *
     * @param slug the URL-friendly slug (e.g., "spring-boot")
     * @return the category response
     */
    @GetMapping("/slug/{slug}")
    public ResponseEntity<CategoryResponse> getCategoryBySlug(@PathVariable String slug) {
        log.info("Fetching category by slug: '{}'", slug);

        CategoryResponse category = categoryService.getCategoryBySlug(slug);

        return ResponseEntity.ok(category);
    }

    /**
     * Update an existing category.
     * Only ADMIN can modify category details.
     * Slug is regenerated if the name changes.
     *
     * @param id      the category ID
     * @param request the update request (name, description)
     * @return the updated category response
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CategoryResponse> updateCategory(
            @PathVariable Long id,
            @Valid @RequestBody CreateCategoryRequest request) {

        log.info("Updating category ID: {} with name: '{}'", id, request.getName());

        CategoryResponse response = categoryService.updateCategory(id, request);

        log.info("Category ID: {} updated, new slug: '{}'", response.getId(), response.getSlug());

        return ResponseEntity.ok(response);
    }

    /**
     * Delete a category.
     * Only ADMIN can remove categories.
     * Will fail if any posts are currently assigned to this category.
     *
     * @param id the category ID
     * @return 204 No Content on success
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteCategory(@PathVariable Long id) {
        log.info("Deleting category ID: {}", id);

        categoryService.deleteCategory(id);

        log.info("Category ID: {} deleted successfully", id);

        return ResponseEntity.noContent().build();
    }
}

