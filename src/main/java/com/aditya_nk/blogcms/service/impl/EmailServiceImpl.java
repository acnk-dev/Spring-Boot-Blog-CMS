package com.aditya_nk.blogcms.service.impl;

import com.aditya_nk.blogcms.dto.EmailRequest;
import com.aditya_nk.blogcms.dto.EmailType;
import com.aditya_nk.blogcms.entity.Comment;
import com.aditya_nk.blogcms.entity.Post;
import com.aditya_nk.blogcms.entity.User;
import com.aditya_nk.blogcms.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Simulated email service that logs structured output instead of contacting a
 * real SMTP server.  Every public method is annotated {@code @Async} so that
 * the calling thread (e.g. the HTTP request thread in AuthServiceImpl) is
 * never blocked waiting for the simulated 2-second "send" delay.
 *
 * <p>All exceptions — including {@link InterruptedException} — are caught and
 * logged rather than re-thrown.  Async {@code void} methods that propagate
 * exceptions cause the exception to be silently swallowed by the framework;
 * explicit catch-and-log is far easier to diagnose in production logs.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Simulate the latency of an SMTP round-trip and emit a structured log
     * line that shows exactly what would have been sent.
     */
    private void simulateSend(EmailRequest request) {
        log.info("[EMAIL] type={} to={} subject=\"{}\" thread={} timestamp={}",
                request.getType(),
                request.getTo(),
                request.getSubject(),
                Thread.currentThread().getName(),
                LocalDateTime.now());
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            // Restore the interrupt flag so calling code can detect it if needed.
            Thread.currentThread().interrupt();
            log.error("[EMAIL] Send interrupted: type={} to={}", request.getType(), request.getTo());
            return;
        }
        log.info("[EMAIL] Sent successfully: type={} to={}", request.getType(), request.getTo());
    }

    // ── EmailService methods ─────────────────────────────────────────────────

    @Override
    @Async("taskExecutor")
    public void sendWelcomeEmail(User user) {
        try {
            EmailRequest request = EmailRequest.builder()
                    .to(user.getEmail())
                    .subject("Welcome to Blog CMS, " + user.getName() + "!")
                    .body("Hi " + user.getName() + ",\n\nYour account has been created successfully.")
                    .type(EmailType.WELCOME)
                    .build();

            log.info("[EMAIL] Preparing WELCOME email for user id={} name={}", user.getId(), user.getName());
            simulateSend(request);
        } catch (Exception e) {
            log.error("[EMAIL] Failed to send WELCOME email to {}: {}", user.getEmail(), e.getMessage(), e);
        }
    }

    @Override
    @Async("taskExecutor")
    public void sendPasswordResetEmail(User user, String resetToken) {
        try {
            String resetUrl = "https://blogcms.local/reset-password?token=" + resetToken;

            EmailRequest request = EmailRequest.builder()
                    .to(user.getEmail())
                    .subject("Password Reset Request")
                    .body("Hi " + user.getName() + ",\n\nClick the link to reset your password:\n" + resetUrl)
                    .type(EmailType.PASSWORD_RESET)
                    .build();

            log.info("[EMAIL] Preparing PASSWORD_RESET email for user id={} resetUrl={}", user.getId(), resetUrl);
            simulateSend(request);
        } catch (Exception e) {
            log.error("[EMAIL] Failed to send PASSWORD_RESET email to {}: {}", user.getEmail(), e.getMessage(), e);
        }
    }

    @Override
    @Async("taskExecutor")
    public void sendCommentNotification(User postAuthor, Comment comment) {
        try {
            String postTitle = comment.getPost() != null ? comment.getPost().getTitle() : "(unknown post)";
            String commenterName = comment.getAuthor() != null ? comment.getAuthor().getName() : "Someone";

            EmailRequest request = EmailRequest.builder()
                    .to(postAuthor.getEmail())
                    .subject("New comment on your post: " + postTitle)
                    .body("Hi " + postAuthor.getName() + ",\n\n"
                            + commenterName + " commented on \"" + postTitle + "\":\n\n"
                            + comment.getBody())
                    .type(EmailType.COMMENT_NOTIFICATION)
                    .build();

            log.info("[EMAIL] Preparing COMMENT_NOTIFICATION email: postAuthorId={} commentId={}",
                    postAuthor.getId(), comment.getId());
            simulateSend(request);
        } catch (Exception e) {
            log.error("[EMAIL] Failed to send COMMENT_NOTIFICATION email to {}: {}",
                    postAuthor.getEmail(), e.getMessage(), e);
        }
    }

    @Override
    @Async("taskExecutor")
    public void sendWeeklyDigest(User user, List<Post> popularPosts) {
        try {
            String postSummary = popularPosts.stream()
                    .map(p -> "  - " + p.getTitle())
                    .collect(Collectors.joining("\n"));

            log.info("[EMAIL] Preparing WEEKLY_DIGEST for user id={} postCount={} posts=[{}]",
                    user.getId(),
                    popularPosts.size(),
                    popularPosts.stream().map(Post::getTitle).collect(Collectors.joining(", ")));

            EmailRequest request = EmailRequest.builder()
                    .to(user.getEmail())
                    .subject("Your Weekly Blog Digest")
                    .body("Hi " + user.getName() + ",\n\nThis week's popular posts:\n\n" + postSummary)
                    .type(EmailType.WEEKLY_DIGEST)
                    .build();

            simulateSend(request);
        } catch (Exception e) {
            log.error("[EMAIL] Failed to send WEEKLY_DIGEST email to {}: {}", user.getEmail(), e.getMessage(), e);
        }
    }
}

