package com.aditya_nk.blogcms.repository;

import com.aditya_nk.blogcms.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Repository interface for Tag entity.
 * Provides CRUD operations and custom query methods for tag management.
 *
 * Spring Data JPA automatically implements these methods at runtime.
 */
@Repository
public interface TagRepository extends JpaRepository<Tag, Long> {

    /**
     * Find tag by slug.
     * Used for URL routing (e.g., /tags/spring-boot).
     *
     * @param slug the URL-friendly identifier
     * @return Optional containing tag if found, empty otherwise
     */
    Optional<Tag> findBySlug(String slug);

    /**
     * Find tag by name.
     * Case-sensitive exact match.
     *
     * @param name the tag name
     * @return Optional containing tag if found, empty otherwise
     */
    Optional<Tag> findByName(String name);

    /**
     * Find multiple tags by their names.
     * Used when processing tag input from forms (comma-separated tags).
     *
     * @param names list of tag names to find
     * @return List of tags matching any of the provided names
     */
    List<Tag> findByNameIn(List<String> names);

    /**
     * Find multiple tags by their names (Set version).
     * Used when processing unique tag names.
     *
     * @param names set of tag names to find
     * @return Set of tags matching any of the provided names
     */
    Set<Tag> findByNameIn(Set<String> names);

    /**
     * Check if tag exists with given name.
     * Used for validation when creating tags.
     *
     * @param name the tag name to check
     * @return true if tag exists, false otherwise
     */
    boolean existsByName(String name);

    /**
     * Check if tag exists with given slug.
     * Used for validation when creating tags.
     *
     * @param slug the slug to check
     * @return true if tag exists, false otherwise
     */
    boolean existsBySlug(String slug);

    /**
     * Find all tags ordered by name.
     * Used for tag listing and tag clouds.
     *
     * @return List of tags sorted alphabetically
     */
    List<Tag> findAllByOrderByNameAsc();

    /**
     * Search tags by name.
     * Uses case-insensitive LIKE search.
     * Used for admin search and autocomplete.
     *
     * @param searchTerm the term to search for
     * @return List of tags matching search criteria
     */
    @Query("SELECT t FROM Tag t WHERE LOWER(t.name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    List<Tag> searchByName(@Param("searchTerm") String searchTerm);

    /**
     * Find tags starting with given prefix.
     * Used for autocomplete when tagging posts.
     *
     * @param prefix the prefix to match
     * @return List of tags with names starting with prefix
     */
    @Query("SELECT t FROM Tag t WHERE LOWER(t.name) LIKE LOWER(CONCAT(:prefix, '%')) ORDER BY t.name")
    List<Tag> findByNameStartingWith(@Param("prefix") String prefix);

    /**
     * Find popular tags based on post count.
     * Returns tags ordered by number of associated posts.
     * Used for tag clouds and popular tags sections.
     *
     * @param limit maximum number of tags to return
     * @return List of popular tags with post counts
     */
    @Query("SELECT t FROM Tag t LEFT JOIN t.posts p " +
            "WHERE p.isDeleted = false AND p.status = 'PUBLISHED' " +
            "GROUP BY t ORDER BY COUNT(p) DESC")
    List<Tag> findPopularTags(@Param("limit") int limit);

    /**
     * Find tags used by a specific author.
     * Returns tags that appear on posts by the given user.
     *
     * @param userId the author's user ID
     * @return List of tags used by the author
     */
    @Query("SELECT DISTINCT t FROM Tag t JOIN t.posts p " +
            "WHERE p.user.id = :userId AND p.isDeleted = false")
    List<Tag> findTagsByAuthor(@Param("userId") Long userId);
}
