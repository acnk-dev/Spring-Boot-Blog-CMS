package com.aditya_nk.blogcms.service;


import com.aditya_nk.blogcms.entity.Comment;
import com.aditya_nk.blogcms.entity.Post;
import com.aditya_nk.blogcms.entity.User;

import java.util.List;

/**
 * Contract for all transactional email operations.
 * Every implementation must run asynchronously — callers should never block
 * waiting for an email to be delivered.
 */
public interface EmailService {

    /**
     * Send a welcome email to a newly registered user.
     *
     * @param user the freshly persisted User entity
     */
    void sendWelcomeEmail(User user);

    /**
     * Send a password-reset link to the user.
     *
     * @param user       the user requesting a reset
     * @param resetToken the short-lived token embedded in the reset URL
     */
    void sendPasswordResetEmail(User user, String resetToken);

    /**
     * Notify a post author that someone left a comment on their post.
     *
     * @param postAuthor the author who should receive the notification
     * @param comment    the comment that was just created
     */
    void sendCommentNotification(User postAuthor, Comment comment);

    /**
     * Send a weekly digest containing the most popular posts.
     *
     * @param user         the subscriber receiving the digest
     * @param popularPosts ordered list of posts to highlight
     */
    void sendWeeklyDigest(User user, List<Post> popularPosts);
}

