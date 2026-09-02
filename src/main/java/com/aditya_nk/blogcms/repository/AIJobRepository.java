package com.aditya_nk.blogcms.repository;

import com.aditya_nk.blogcms.entity.AIJob;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Deliberately extends the narrow Spring Data {@link Repository} marker
 * interface, not {@code JpaRepository}, declaring only {@code findById}
 * means no save/delete method exists on this interface at all. ai_jobs is
 * owned and written exclusively by blogcms-ai-service.
 */
public interface AIJobRepository extends Repository<AIJob, UUID> {

    Optional<AIJob> findById(UUID id);
}
