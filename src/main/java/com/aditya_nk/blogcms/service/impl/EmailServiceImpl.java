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
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.messaging.MessagingException;
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
 * <p>Emails are rendered as HTML using inline-CSS string templates built in
 * private {@code buildXxxHtml(...)} methods.  No external template engine is
 * used — the full HTML is visible in this single file, which makes it easy for
 * students to understand exactly what produces the rendered output.
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

    private static final String FROM_ADDRESS  = "noreply@blog-cms.local";
    private static final String APP_BASE_URL  = "http://localhost:8082";

    private final JavaMailSender mailSender;

    // ── Shared HTML layout helpers ────────────────────────────────────────────

    /**
     * Returns the opening HTML boilerplate — doctype, head with meta tags,
     * and the outer wrapper table that centres all email content at 600 px.
     */
    private String htmlHeader() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>Blog CMS</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f1f5f9;font-family:'Segoe UI',Arial,sans-serif;">
                  <table role="presentation" cellpadding="0" cellspacing="0" width="100%"
                         style="background-color:#f1f5f9;padding:32px 0;">
                    <tr><td align="center">
                      <table role="presentation" cellpadding="0" cellspacing="0" width="600"
                             style="background-color:#ffffff;border-radius:12px;
                                    box-shadow:0 4px 24px rgba(0,0,0,0.08);overflow:hidden;">

                        <!-- HEADER -->
                        <tr>
                          <td style="background-color:#1e293b;padding:28px 40px;text-align:center;">
                            <span style="color:#ffffff;font-size:22px;font-weight:700;
                                         letter-spacing:0.5px;">Blog CMS</span>
                          </td>
                        </tr>

                        <!-- BODY OPEN -->
                        <tr><td style="padding:40px 40px 32px;">
                """;
    }

    /**
     * Returns the closing HTML — footer row and all closing tags.
     */
    private String htmlFooter() {
        return """
                        </td></tr>

                        <!-- FOOTER -->
                        <tr>
                          <td style="background-color:#f8fafc;padding:20px 40px;text-align:center;
                                     border-top:1px solid #e2e8f0;">
                            <p style="margin:0;font-size:12px;color:#94a3b8;">
                              You are receiving this email because you have an account on Blog CMS.<br>
                              &copy; 2026 Blog CMS. All rights reserved.
                            </p>
                          </td>
                        </tr>

                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """;
    }

    /**
     * Renders a prominent CTA button centered in the email.
     *
     * @param label button text
     * @param url   destination URL
     */
    private String ctaButton(String label, String url) {
        return """
                <table role="presentation" cellpadding="0" cellspacing="0"
                       style="margin:32px auto 0;">
                  <tr>
                    <td style="background-color:#6366f1;border-radius:8px;">
                      <a href="%s"
                         style="display:inline-block;padding:14px 32px;color:#ffffff;
                                font-size:15px;font-weight:600;text-decoration:none;
                                letter-spacing:0.3px;">%s</a>
                    </td>
                  </tr>
                </table>
                """.formatted(url, label);
    }

    // ── HTML template builders ────────────────────────────────────────────────

    /**
     * Builds the Welcome email HTML for a newly registered user.
     */
    private String buildWelcomeHtml(User user) {
        String body = """
                <h2 style="margin:0 0 16px;font-size:24px;color:#1e293b;font-weight:700;">
                  Welcome to Blog CMS, %s! &#127881;
                </h2>
                <p style="margin:0 0 12px;font-size:15px;line-height:1.7;color:#475569;">
                  We're thrilled to have you here. Your account is all set up and ready to go.
                </p>
                <p style="margin:0 0 12px;font-size:15px;line-height:1.7;color:#475569;">
                  You can browse and read all published articles on the platform straight away.
                  If you've been granted the <strong style="color:#6366f1;">AUTHOR</strong> role,
                  you can also create and publish your own articles — share your ideas with
                  the world!
                </p>
                <p style="margin:0;font-size:15px;line-height:1.7;color:#475569;">
                  Happy reading, and welcome aboard.
                </p>
                %s
                """.formatted(escapeHtml(user.getName()), ctaButton("Start Exploring", APP_BASE_URL));

        return htmlHeader() + body + htmlFooter();
    }

    /**
     * Builds the Password Reset email HTML containing a time-limited reset link.
     */
    private String buildPasswordResetHtml(User user, String resetUrl) {
        String body = """
                <h2 style="margin:0 0 16px;font-size:24px;color:#1e293b;font-weight:700;">
                  Reset Your Password
                </h2>
                <p style="margin:0 0 12px;font-size:15px;line-height:1.7;color:#475569;">
                  Hi %s,
                </p>
                <p style="margin:0 0 12px;font-size:15px;line-height:1.7;color:#475569;">
                  We received a request to reset the password for your Blog CMS account.
                  Click the button below to choose a new password.
                </p>
                <p style="margin:0 0 24px;font-size:15px;line-height:1.7;color:#475569;">
                  <strong>This link expires in 30 minutes.</strong>
                  After that you'll need to request a new reset link.
                </p>
                %s
                <p style="margin:32px 0 0;font-size:13px;line-height:1.6;color:#94a3b8;
                           background-color:#fef9c3;border-left:4px solid #fbbf24;
                           padding:12px 16px;border-radius:4px;">
                  If you did not request a password reset, you can safely ignore this email.
                  Your password will not change unless you click the link above.
                </p>
                """.formatted(escapeHtml(user.getName()), ctaButton("Reset Password", resetUrl));

        return htmlHeader() + body + htmlFooter();
    }

    /**
     * Builds the Comment Notification email HTML alerting a post author to a new comment.
     */
    private String buildCommentNotificationHtml(User postAuthor, String commenterName,
                                                String postTitle, String commentBody) {
        String body = """
                <h2 style="margin:0 0 16px;font-size:24px;color:#1e293b;font-weight:700;">
                  New comment on your post
                </h2>
                <p style="margin:0 0 12px;font-size:15px;line-height:1.7;color:#475569;">
                  Hi %s,
                </p>
                <p style="margin:0 0 20px;font-size:15px;line-height:1.7;color:#475569;">
                  <strong style="color:#1e293b;">%s</strong> commented on your post
                  <em>&ldquo;%s&rdquo;</em>:
                </p>
                <!-- Comment blockquote -->
                <blockquote style="margin:0 0 24px;padding:16px 20px;
                                   background-color:#f8fafc;
                                   border-left:4px solid #6366f1;
                                   border-radius:0 8px 8px 0;">
                  <p style="margin:0;font-size:15px;line-height:1.7;color:#475569;font-style:italic;">
                    &ldquo;%s&rdquo;
                  </p>
                </blockquote>
                <p style="margin:0 0 0;font-size:15px;line-height:1.7;color:#475569;">
                  Log in to reply or moderate the comment.
                </p>
                %s
                """.formatted(
                escapeHtml(postAuthor.getName()),
                escapeHtml(commenterName),
                escapeHtml(postTitle),
                escapeHtml(commentBody),
                ctaButton("View Comment", APP_BASE_URL));

        return htmlHeader() + body + htmlFooter();
    }

    /**
     * Builds the Weekly Digest email HTML listing this week's most popular posts.
     */
    private String buildWeeklyDigestHtml(User user, List<Post> popularPosts) {
        String postCards = popularPosts.stream()
                .map(p -> """
                        <tr>
                          <td style="padding:0 0 12px;">
                            <div style="background-color:#f8fafc;border-left:4px solid #6366f1;
                                        border-radius:0 8px 8px 0;padding:14px 18px;">
                              <p style="margin:0;font-size:15px;font-weight:600;color:#1e293b;">
                                %s
                              </p>
                            </div>
                          </td>
                        </tr>
                        """.formatted(escapeHtml(p.getTitle())))
                .collect(Collectors.joining());

        String body = """
                <h2 style="margin:0 0 16px;font-size:24px;color:#1e293b;font-weight:700;">
                  Your Weekly Blog Digest &#128240;
                </h2>
                <p style="margin:0 0 8px;font-size:15px;line-height:1.7;color:#475569;">
                  Hi %s,
                </p>
                <p style="margin:0 0 24px;font-size:15px;line-height:1.7;color:#475569;">
                  Here's what's been popular this week on Blog CMS:
                </p>
                <table role="presentation" cellpadding="0" cellspacing="0" width="100%%">
                  %s
                </table>
                %s
                """.formatted(
                escapeHtml(user.getName()),
                postCards,
                ctaButton("Read All Posts", APP_BASE_URL));

        return htmlHeader() + body + htmlFooter();
    }

    // ── Core send helper ──────────────────────────────────────────────────────

    /**
     * Creates a {@link MimeMessage}, populates it via {@link MimeMessageHelper}
     * with the HTML body, and dispatches it through the configured SMTP server.
     *
     * <p>{@code MimeMessageHelper} handles the character encoding and sets the
     * {@code Content-Type} to {@code text/html; charset=UTF-8} when
     * {@code isHtml = true} is passed to {@link MimeMessageHelper#setText}.
     *
     * @param to       recipient address
     * @param subject  email subject line
     * @param htmlBody fully rendered HTML string
     * @param type     email type used only for structured logging
     */
    private void doSend(String to, String subject, String htmlBody, EmailType type) {
        log.info("[EMAIL] Sending type={} to={} subject=\"{}\" thread={}",
                type, to, subject, Thread.currentThread().getName());
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, "UTF-8");
            helper.setFrom(FROM_ADDRESS);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true); // true = isHtml → Content-Type: text/html
            mailSender.send(mimeMessage);
            log.info("[EMAIL] Sent successfully: type={} to={}", type, to);
        } catch (MessagingException e) {
            log.error("[EMAIL] SMTP failure: type={} to={} error={}", type, to, e.getMessage(), e);
        } catch (Exception e) {
            log.error("[EMAIL] Unexpected failure: type={} to={} error={}", type, to, e.getMessage(), e);
        }
    }

    // ── EmailService public methods ───────────────────────────────────────────

    @Override
    @Async("taskExecutor")
    public void sendWelcomeEmail(User user) {
        try {
            log.info("[EMAIL] Preparing WELCOME email for user id={} name={}", user.getId(), user.getName());
            String html = buildWelcomeHtml(user);
            doSend(user.getEmail(), "Welcome to Blog CMS!", html, EmailType.WELCOME);
        } catch (Exception e) {
            log.error("[EMAIL] Failed to prepare WELCOME email for user id={}: {}", user.getId(), e.getMessage(), e);
        }
    }

    @Override
    @Async("taskExecutor")
    public void sendPasswordResetEmail(User user, String resetToken) {
        try {
            String resetUrl = "https://blogcms.local/reset-password?token=" + resetToken;
            log.info("[EMAIL] Preparing PASSWORD_RESET email for user id={} resetUrl={}", user.getId(), resetUrl);
            String html = buildPasswordResetHtml(user, resetUrl);
            doSend(user.getEmail(), "Reset Your Blog CMS Password", html, EmailType.PASSWORD_RESET);
        } catch (Exception e) {
            log.error("[EMAIL] Failed to prepare PASSWORD_RESET email for user id={}: {}", user.getId(), e.getMessage(), e);
        }
    }

    @Override
    @Async("taskExecutor")
    public void sendCommentNotification(User postAuthor, Comment comment) {
        try {
            String postTitle     = comment.getPost()   != null ? comment.getPost().getTitle()   : "(unknown post)";
            String commenterName = comment.getAuthor() != null ? comment.getAuthor().getName()  : "Someone";
            String commentBody   = comment.getBody()   != null ? comment.getBody()              : "(no content)";

            log.info("[EMAIL] Preparing COMMENT_NOTIFICATION email: postAuthorId={} commentId={}",
                    postAuthor.getId(), comment.getId());

            String html = buildCommentNotificationHtml(postAuthor, commenterName, postTitle, commentBody);
            doSend(postAuthor.getEmail(), "New comment on your post: " + postTitle, html,
                    EmailType.COMMENT_NOTIFICATION);
        } catch (Exception e) {
            log.error("[EMAIL] Failed to prepare COMMENT_NOTIFICATION email for postAuthorId={}: {}",
                    postAuthor.getId(), e.getMessage(), e);
        }
    }

    @Override
    @Async("taskExecutor")
    public void sendWeeklyDigest(User user, List<Post> popularPosts) {
        try {
            log.info("[EMAIL] Preparing WEEKLY_DIGEST for user id={} postCount={} posts=[{}]",
                    user.getId(),
                    popularPosts.size(),
                    popularPosts.stream().map(Post::getTitle).collect(Collectors.joining(", ")));

            String html = buildWeeklyDigestHtml(user, popularPosts);
            doSend(user.getEmail(), "Your Weekly Blog Digest", html, EmailType.WEEKLY_DIGEST);
        } catch (Exception e) {
            log.error("[EMAIL] Failed to prepare WEEKLY_DIGEST email for user id={}: {}", user.getId(), e.getMessage(), e);
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /**
     * Minimal HTML escaping to prevent XSS in user-supplied strings that are
     * interpolated directly into the HTML template.  Only covers the five
     * characters that must always be escaped in HTML content.
     */
    private String escapeHtml(String input) {
        if (input == null) return "";
        return input
                .replace("&",  "&amp;")
                .replace("<",  "&lt;")
                .replace(">",  "&gt;")
                .replace("\"", "&quot;")
                .replace("'",  "&#x27;");
    }
}

