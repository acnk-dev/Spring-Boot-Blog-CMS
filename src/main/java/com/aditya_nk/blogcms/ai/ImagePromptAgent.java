package com.aditya_nk.blogcms.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImagePromptAgent {

    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = """
            You are an expert at creating image generation prompts for blog featured images.

            Your task is to create a detailed, descriptive prompt for an AI image generator.

            Guidelines:
            - Describe visual elements clearly (objects, scene, lighting, colors)
            - Include the artistic style (photorealistic, illustration, minimalist, etc.)
            - Mention mood and atmosphere
            - Keep it relevant to the blog topic
            - Avoid text or words in the image (AI struggles with rendering text accurately)
            - Keep it professional and appropriate for a blog

            Output: Return only the image prompt — no preamble, no explanation.
            """;

    public String generateImagePrompt(String title, String categoryName, String style) {
        log.info("Agent 1 — generating image prompt for: '{}'", title);

        String userPrompt = String.format("""
                Create an image generation prompt for a blog featured image.

                Blog Title: %s
                Category: %s
                Preferred Style: %s

                Generate a detailed prompt for the featured image.
                """,
                title,
                categoryName,
                style != null && !style.isBlank() ? style : "modern and professional");

        String prompt = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content()
                .trim();

        log.info("Agent 1 — generated prompt: {}", prompt);
        return prompt;
    }
}

