package com.aditya_nk.blogcms.repository;

import com.aditya_nk.blogcms.entity.User;
import com.aditya_nk.blogcms.enums.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for User entity.
 * Provides CRUD operations and custom query methods for user management.
 *
 * Spring Data JPA automatically implements these methods at runtime.
 * Methods following naming conventions are auto-implemented.
 * Custom queries use @Query annotation.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Find user by email address.
     * Includes both active and soft-deleted users.
     *
     * @param email the email address to search for
     * @return Optional containing user if found, empty otherwise
     */
    Optional<User> findByEmail(String email);

    /**
     * Find active user by email address.
     * Excludes soft-deleted users.
     *
     * @param email the email address to search for
     * @return Optional containing active user if found, empty otherwise
     */
    Optional<User> findByEmailAndIsDeletedFalse(String email);

    /**
     * Check if any user exists with given email.
     * Includes both active and soft-deleted users.
     * Useful for registration validation.
     *
     * @param email the email address to check
     * @return true if user exists, false otherwise
     */
    boolean existsByEmail(String email);

    /**
     * Check if active user exists with given email.
     * Excludes soft-deleted users.
     *
     * @param email the email address to check
     * @return true if active user exists, false otherwise
     */
    boolean existsByEmailAndIsDeletedFalse(String email);

    /**
     * Find all users with specific role.
     * Includes both active and soft-deleted users.
     *
     * @param role the role to filter by
     * @return List of users with specified role
     */
    List<User> findByRole(Role role);

    /**
     * Find all active users with specific role.
     * Excludes soft-deleted users.
     * Useful for admin dashboards (e.g., "Show all authors").
     *
     * @param role the role to filter by
     * @return List of active users with specified role
     */
    List<User> findByRoleAndIsDeletedFalse(Role role);

    /**
     * Find all active users.
     * Excludes soft-deleted users.
     * Default query for listing users.
     *
     * @return List of all active users
     */
    List<User> findByIsDeletedFalse();

    /**
     * Count total active users.
     * Excludes soft-deleted users.
     * Useful for analytics and dashboard metrics.
     *
     * @return count of active users
     */
    long countByIsDeletedFalse();

    /**
     * Count users by role.
     * Includes both active and soft-deleted users.
     *
     * @param role the role to count
     * @return count of users with specified role
     */
    long countByRole(Role role);

    /**
     * Find all active users with unverified emails.
     * Useful for sending reminder emails or filtering incomplete profiles.
     *
     * @return List of active users who haven't verified email
     */
    List<User> findByEmailVerifiedFalseAndIsDeletedFalse();

    /**
     * Search active users by name or email.
     * Uses case-insensitive LIKE search on name and email fields.
     * Excludes soft-deleted users.
     *
     * @param searchTerm the term to search for (will be wrapped with % for LIKE)
     * @return List of users matching search criteria
     */
    @Query("SELECT u FROM User u WHERE u.isDeleted = false " +
            "AND (LOWER(u.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
            "OR LOWER(u.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
    List<User> searchActiveUsers(@Param("searchTerm") String searchTerm);

    // ── Cleanup / retention queries ──────────────────────────────────────────

    /**
     * Find soft-deleted users whose updatedAt timestamp is older than the cutoff.
     * Used by the cleanup scheduler to identify candidates for hard-deletion.
     *
     * @param cutoff anything updated before this datetime is eligible
     * @return list of users past the retention window
     */
    List<User> findByIsDeletedTrueAndUpdatedAtBefore(LocalDateTime cutoff);

    /**
     * Hard-delete users that have been soft-deleted AND are past the retention cutoff.
     * Uses a JPQL DELETE statement — avoids loading entities into memory.
     *
     * @param cutoff datetime threshold; users with updatedAt before this are removed
     * @return number of rows deleted
     */
    @Modifying
    @Query("DELETE FROM User u WHERE u.isDeleted = true AND u.updatedAt < :cutoff")
    int hardDeleteByIsDeletedTrueAndUpdatedAtBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Count users that are currently soft-deleted.
     * Used by the stats job to report on accumulated soft-deleted records.
     *
     * @return count of soft-deleted users
     */
    long countByIsDeletedTrue();

    @Modifying
    @Query("UPDATE User u SET u.updatedAt = :updatedAt WHERE u.id = :id")
    void backdateUpdatedAt(@Param("id") Long id, @Param("updatedAt") LocalDateTime updatedAt);
}
