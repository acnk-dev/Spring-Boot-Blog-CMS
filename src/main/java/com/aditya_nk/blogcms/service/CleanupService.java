package com.aditya_nk.blogcms.service;

/**
 * Service interface for scheduled database cleanup operations.
 *
 * Each method corresponds to one scheduled job that runs on a cron schedule
 * defined in application.properties. Separating the contract (this interface)
 * from the implementation (CleanupServiceImpl) keeps the scheduler wiring
 * independent from the business logic — the same pattern used throughout this
 * project for PostService, UserService, etc.
 *
 * Note: RefreshToken entity does not exist in this project, so no token
 * cleanup method is declared here.
 */
public interface CleanupService {

    /**
     * Hard-delete posts that have been soft-deleted for longer than the
     * configured retention window (default: 30 days).
     *
     * Only posts with isDeleted = true AND updatedAt older than the cutoff
     * are permanently removed. Active posts are never touched.
     */
    void purgeDeletedPosts();

    /**
     * Hard-delete users that have been soft-deleted for longer than the
     * configured retention window (default: 30 days).
     *
     * Same retention logic as purgeDeletedPosts. Orphaned posts from these
     * users are handled by database cascade rules.
     */
    void purgeDeletedUsers();

    /**
     * Log current database health statistics: counts of soft-deleted posts,
     * soft-deleted users, and other relevant metrics.
     *
     * Uses COUNT queries only — no entities are loaded into memory.
     * This job runs every 6 hours so operators can monitor accumulation trends.
     */
    void logDatabaseStats();
}

