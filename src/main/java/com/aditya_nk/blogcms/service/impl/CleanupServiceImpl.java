package com.aditya_nk.blogcms.service.impl;

import com.aditya_nk.blogcms.repository.PostRepository;
import com.aditya_nk.blogcms.repository.UserRepository;
import com.aditya_nk.blogcms.service.CleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Implements scheduled database cleanup jobs for this Blog CMS.
 *
 * Design decisions:
 *  - @Transactional on each method: JPQL DELETE (@Modifying) requires an
 *    active transaction. The class-level @Transactional(readOnly = true)
 *    default used by other service impls is intentionally NOT applied here
 *    because all three methods are either writes or treated individually.
 *  - @Scheduled cron expressions are externalised to application.properties
 *    so operators can adjust schedules without recompiling.
 *  - Duration tracking uses System.currentTimeMillis() — simple, zero-dep,
 *    appropriate for coarse-grained job telemetry in log output.
 *  - RefreshToken does not exist in this project, so no token purge job.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CleanupServiceImpl implements CleanupService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;

    /**
     * Number of days a soft-deleted record is kept before hard-deletion.
     * Defaults to 30 if the property is absent (e.g., in test context).
     */
    @Value("${cleanup.deleted-retention-days:30}")
    private int retentionDays;

    /**
     * Purge posts soft-deleted more than {@code retentionDays} days ago.
     *
     * Fires at 02:00 every night (low-traffic window).
     * Uses a direct JPQL DELETE to avoid loading post entities into memory —
     * the same principle as @Modifying increment/decrement queries in PostRepository.
     */
    @Override
    @Scheduled(cron = "${cleanup.cron.purge-posts}")
    @Transactional
    public void purgeDeletedPosts() {
        long start = System.currentTimeMillis();
        log.info("[CleanupJob] purgeDeletedPosts starting — retention window: {} days", retentionDays);

        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted = postRepository.hardDeleteByIsDeletedTrueAndUpdatedAtBefore(cutoff);

        long duration = System.currentTimeMillis() - start;
        log.info("[CleanupJob] purgeDeletedPosts complete — {} post(s) hard-deleted in {}ms", deleted, duration);
    }

    /**
     * Purge users soft-deleted more than {@code retentionDays} days ago.
     *
     * Fires at 02:30 every night — 30 minutes after the post purge so both
     * jobs are not competing for database I/O at the same instant.
     */
    @Override
    @Scheduled(cron = "${cleanup.cron.purge-users}")
    @Transactional
    public void purgeDeletedUsers() {
        long start = System.currentTimeMillis();
        log.info("[CleanupJob] purgeDeletedUsers starting — retention window: {} days", retentionDays);

        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted = userRepository.hardDeleteByIsDeletedTrueAndUpdatedAtBefore(cutoff);

        long duration = System.currentTimeMillis() - start;
        log.info("[CleanupJob] purgeDeletedUsers complete — {} user(s) hard-deleted in {}ms", deleted, duration);
    }

    /**
     * Log counts of soft-deleted records for operational monitoring.
     *
     * Fires every 6 hours. Uses COUNT queries only — no entities are loaded.
     * Output is visible in application logs so operators can set alerts if
     * soft-deleted counts grow unexpectedly (e.g., a buggy batch delete).
     */
    @Override
    @Scheduled(cron = "${cleanup.cron.stats}")
    @Transactional(readOnly = true)
    public void logDatabaseStats() {
        long start = System.currentTimeMillis();
        log.info("[CleanupJob] logDatabaseStats starting");

        long softDeletedPosts = postRepository.countByIsDeletedTrue();
        long softDeletedUsers = userRepository.countByIsDeletedTrue();

        long duration = System.currentTimeMillis() - start;
        log.info("[CleanupJob] logDatabaseStats complete in {}ms — " +
                        "soft-deleted posts: {}, soft-deleted users: {}",
                duration, softDeletedPosts, softDeletedUsers);
    }
}

