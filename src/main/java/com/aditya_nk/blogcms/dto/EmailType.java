package com.aditya_nk.blogcms.dto;

/**
 * Enumerates the types of transactional emails the system can send.
 * Each value maps to a distinct template and delivery trigger.
 */
public enum EmailType {
    WELCOME,
    PASSWORD_RESET,
    COMMENT_NOTIFICATION,
    WEEKLY_DIGEST
}
