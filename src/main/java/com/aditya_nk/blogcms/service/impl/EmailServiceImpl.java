package com.aditya_nk.blogcms.service.impl;

import com.aditya_nk.blogcms.dto.EmailRequest;
import com.aditya_nk.blogcms.dto.EmailType;
import com.aditya_nk.blogcms.entity.Comment;
import com.aditya_nk.blogcms.entity.Post;
import com.aditya_nk.blogcms.entity.User;
import com.aditya_nk.blogcms.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Real SMTP email service backed by Mailtrap sandbox.
 *
 * <p>Every method is annotated {@code @Async("taskExecutor")} so the HTTP
 * request thread is never blocked waiting for the SMTP round-trip.  All
 * exceptions are caught and logged — async {@code void} methods must never
 * propagate exceptions because there is no caller thread left to receive them.
 *
 * <p>Configure Mailtrap credentials via environment variables:
 * <pre>
 *   MAILTRAP_USERNAME=&lt;your sandbox username&gt;
 *   MAILTRAP_PASSWORD=&lt;your sandbox password&gt;
 * </pre>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private static final String FROM_ADDRESS = "noreply@blog-cms.local";

    private final JavaMailSender mailSender;

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * Build a {@link SimpleMailMessage}, log the intent, send via SMTP, then
     * log success.  The caller wraps this in a try/catch so any
     * {@link org.springframework.mail.MailException} is handled consistently.
     */
    private void doSend(EmailRequest request) {
        log.info("[EMAIL] Sending type={} to={} subject=\"{}\" thread={}",
                request.getType(),
                request.getTo(),
                request.getSubject(),
                Thread.currentThread().getName());

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(FROM_ADDRESS);
        message.setTo(request.getTo());
        message.setSubject(request.getSubject());
        message.setText(request.getBody());

        mailSender.send(message);

        log.info("[EMAIL] Sent successfully: type={} to={}", request.getType(), request.getTo());
    }

    // ── EmailService methods ──────────────────────────────────────────────────

    @Override
    @Async("taskExecutor")
    public void sendWelcomeEmail(User user) {
        try {
            String body = "Hi " + user.getName() + ",\n\n"
                    + "Welcome to Blog CMS! Your account has been created successfully.\n\n"
                    + "You can now log in, explore published posts, and — if you have the AUTHOR role — "
                    + "start writing your own articles.\n\n"
                    + "Happy reading!\n"
                    + "The Blog CMS Team";

            EmailRequest request = EmailRequest.builder()
                    .to(user.getEmail())
                    .subject("Welcome to Blog CMS!")
                    .body(body)
                    .type(EmailType.WELCOME)
                    .build();

            log.info("[EMAIL] Preparing WELCOME email for user id={} name={}", user.getId(), user.getName());
            doSend(request);
        } catch (Exception e) {
            log.error("[EMAIL] Failed to send WELCOME email to {}: {}", user.getEmail(), e.getMessage(), e);
        }
    }

    @Override
    @Async("taskExecutor")
    public void sendPasswordResetEmail(User user, String resetToken) {
        try {
            String resetUrl = "https://blogcms.local/reset-password?token=" + resetToken;

            String body = "Hi " + user.getName() + ",\n\n"
                    + "We received a request to reset your password.\n\n"
                    + "Use the link below to choose a new password. "
                    + "This link expires in 30 minutes.\n\n"
                    + resetUrl + "\n\n"
                    + "If you did not request a password reset, you can safely ignore this email.\n\n"
                    + "The Blog CMS Team";

            EmailRequest request = EmailRequest.builder()
                    .to(user.getEmail())
                    .subject("Password Reset Request")
                    .body(body)
                    .type(EmailType.PASSWORD_RESET)
                    .build();

            log.info("[EMAIL] Preparing PASSWORD_RESET email for user id={} resetUrl={}", user.getId(), resetUrl);
            doSend(request);
        } catch (Exception e) {
            log.error("[EMAIL] Failed to send PASSWORD_RESET email to {}: {}", user.getEmail(), e.getMessage(), e);
        }
    }

    @Override
    @Async("taskExecutor")
    public void sendCommentNotification(User postAuthor, Comment comment) {
        try {
            String postTitle    = comment.getPost()   != null ? comment.getPost().getTitle()    : "(unknown post)";
            String commenterName = comment.getAuthor() != null ? comment.getAuthor().getName()  : "Someone";
            String preview       = comment.getBody()  != null ? comment.getBody()               : "(no preview available)";

            String body = "Hi " + postAuthor.getName() + ",\n\n"
                    + commenterName + " left a comment on your post \"" + postTitle + "\":\n\n"
                    + "\"" + preview + "\"\n\n"
                    + "Log in to reply or moderate the comment.\n\n"
                    + "The Blog CMS Team";

            EmailRequest request = EmailRequest.builder()
                    .to(postAuthor.getEmail())
                    .subject("New comment on your post: " + postTitle)
                    .body(body)
                    .type(EmailType.COMMENT_NOTIFICATION)
                    .build();

            log.info("[EMAIL] Preparing COMMENT_NOTIFICATION email: postAuthorId={} commentId={}",
                    postAuthor.getId(), comment.getId());
            doSend(request);
        } catch (Exception e) {
            log.error("[EMAIL] Failed to send COMMENT_NOTIFICATION email to {}: {}",
                    postAuthor.getEmail(), e.getMessage(), e);
        }
    }

    @Override
    @Async("taskExecutor")
    public void sendWeeklyDigest(User user, List<Post> popularPosts) {
        try {
            String postList = popularPosts.stream()
                    .map(p -> "  - " + p.getTitle())
                    .collect(Collectors.joining("\n"));

            String body = "Hi " + user.getName() + ",\n\n"
                    + "Here are this week's most popular posts on Blog CMS:\n\n"
                    + postList + "\n\n"
                    + "Head over to the site to read them in full.\n\n"
                    + "The Blog CMS Team";

            log.info("[EMAIL] Preparing WEEKLY_DIGEST for user id={} postCount={} posts=[{}]",
                    user.getId(),
                    popularPosts.size(),
                    popularPosts.stream().map(Post::getTitle).collect(Collectors.joining(", ")));

            EmailRequest request = EmailRequest.builder()
                    .to(user.getEmail())
                    .subject("Your Weekly Blog Digest")
                    .body(body)
                    .type(EmailType.WEEKLY_DIGEST)
                    .build();

            doSend(request);
        } catch (Exception e) {
            log.error("[EMAIL] Failed to send WEEKLY_DIGEST email to {}: {}", user.getEmail(), e.getMessage(), e);
        }
    }
}

