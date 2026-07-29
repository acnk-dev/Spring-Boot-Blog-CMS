package com.aditya_nk.blogcms.repository;

import com.aditya_nk.blogcms.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Category entity.
 * Provides CRUD operations and custom query methods for category management.
 *
 * Spring Data JPA automatically implements these methods at runtime.
 */
@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /**
     * Find category by slug.
     * Used for URL routing (e.g., /categories/technology).
     *
     * @param slug the URL-friendly identifier
     * @return Optional containing category if found, empty otherwise
     */
    Optional<Category> findBySlug(String slug);

    /**
     * Find category by name.
     * Case-sensitive exact match.
     *
     * @param name the category name
     * @return Optional containing category if found, empty otherwise
     */
    Optional<Category> findByName(String name);

    /**
     * Check if category exists with given name.
     * Used for validation when creating categories.
     *
     * @param name the category name to check
     * @return true if category exists, false otherwise
     */
    boolean existsByName(String name);

    /**
     * Check if category exists with given slug.
     * Used for validation when creating categories.
     *
     * @param slug the slug to check
     * @return true if category exists, false otherwise
     */
    boolean existsBySlug(String slug);

    /**
     * Find all categories ordered by name.
     * Used for category listing and dropdowns.
     *
     * @return List of categories sorted alphabetically
     */
    List<Category> findAllByOrderByNameAsc();

    /**
     * Search categories by name.
     * Uses case-insensitive LIKE search.
     * Used for admin search and autocomplete.
     *
     * @param searchTerm the term to search for
     * @return List of categories matching search criteria
     */
    @Query("SELECT c FROM Category c WHERE LOWER(c.name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    List<Category> searchByName(@Param("searchTerm") String searchTerm);

    /**
     * Find categories starting with given prefix.
     * Used for autocomplete functionality.
     *
     * @param prefix the prefix to match
     * @return List of categories with names starting with prefix
     */
    @Query("SELECT c FROM Category c WHERE LOWER(c.name) LIKE LOWER(CONCAT(:prefix, '%')) ORDER BY c.name")
    List<Category> findByNameStartingWith(@Param("prefix") String prefix);
}

