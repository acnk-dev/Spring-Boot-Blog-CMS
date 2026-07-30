package com.aditya_nk.blogcms.service;

import com.aditya_nk.blogcms.dto.request.CreateCategoryRequest;
import com.aditya_nk.blogcms.dto.response.CategoryResponse;

import java.util.List;

/**
 * Service interface for Category entity operations.
 * Defines the contract for category management business logic.
 */
public interface CategoryService {

    /**
     * Create a new category.
     * Automatically generates a slug from the name.
     *
     * @param request the category creation request
     * @return the created category response
     */
    CategoryResponse createCategory(CreateCategoryRequest request);

    /**
     * Update an existing category.
     * Regenerates slug if name changes.
     *
     * @param id the category ID
     * @param request the update request
     * @return the updated category response
     */
    CategoryResponse updateCategory(Long id, CreateCategoryRequest request);

    /**
     * Delete a category.
     * Will fail if posts are using this category.
     *
     * @param id the category ID
     */
    void deleteCategory(Long id);

    /**
     * Get a category by its ID.
     *
     * @param id the category ID
     * @return the category response
     */
    CategoryResponse getCategoryById(Long id);

    /**
     * Get a category by its slug.
     * Used for URL routing.
     *
     * @param slug the category's URL-friendly slug
     * @return the category response
     */
    CategoryResponse getCategoryBySlug(String slug);

    /**
     * Get all categories ordered by name.
     *
     * @return list of all categories
     */
    List<CategoryResponse> getAllCategories();

    /**
     * Search categories by name.
     *
     * @param searchTerm the search term
     * @return list of matching categories
     */
    List<CategoryResponse> searchCategories(String searchTerm);

    /**
     * Check if a category exists with the given name.
     *
     * @param name the category name
     * @return true if exists, false otherwise
     */
    boolean existsByName(String name);
}

