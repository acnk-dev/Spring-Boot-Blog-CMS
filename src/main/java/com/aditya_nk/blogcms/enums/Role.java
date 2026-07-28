package com.aditya_nk.blogcms.enums;

public enum Role {
    SUBSCRIBER, // Free users - read posts, comments, like
    AUTHOR,  // Paid users - create posts, use AI (limited/unlimited)
    ADMIN // Full access - manage users, moderate content
}
