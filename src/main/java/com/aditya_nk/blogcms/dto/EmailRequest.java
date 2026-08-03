package com.aditya_nk.blogcms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Internal DTO used to pass email parameters between service methods.
 * This is NOT a user-facing request DTO — it carries no Bean Validation
 * annotations because the service constructs it programmatically.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailRequest {

    private String to;
    private String subject;
    private String body;
    private EmailType type;
}

