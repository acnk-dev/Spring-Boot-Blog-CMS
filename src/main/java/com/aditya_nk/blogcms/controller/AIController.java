package com.aditya_nk.blogcms.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AIController {

    private final ChatClient chatClient;

    /**
     * Smoke-test endpoint — verifies the Gemini connection is healthy.
     * Restricted to ADMIN so it is never accidentally exposed to end users.
     */
    @GetMapping("/test")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> testConnection() {
        log.info("AI connection test requested");

        String reply = chatClient.prompt()
                .user("Reply with exactly one sentence: 'Spring AI with Gemini is working correctly.'")
                .call()
                .content();

        log.info("AI test response: {}", reply);
        return ResponseEntity.ok(Map.of("message", reply));
    }
}

