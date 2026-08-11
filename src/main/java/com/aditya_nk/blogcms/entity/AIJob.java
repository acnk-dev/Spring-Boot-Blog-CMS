package com.aditya_nk.blogcms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Maps the ai_jobs table, owned exclusively by blogcms-ai-service — that
 * service is the only writer. This entity is read-only on the monolith
 * side by construction: no @Setter, and AIJobRepository (see repository/)
 * extends the narrower Spring Data Repository interface instead of
 * JpaRepository, so no save()/delete() method exists to call even by
 * accident. See plans/ai-microservice-langchain4j.md ("Async job persistence").
 */
@Entity
@Table(name = "ai_jobs")
@Getter
@NoArgsConstructor
public class AIJob {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "job_type", nullable = false, length = 32)
    private String jobType;

    @Column(nullable = false, length = 16)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String result;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
