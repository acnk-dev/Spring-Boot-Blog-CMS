package com.aditya_nk.blogcms.ai;

import com.aditya_nk.blogcms.entity.Category;
import org.springframework.stereotype.Service;

@Service
public class PromptTemplateService {

    public String getOutlineSystemPrompt(Category category) {
        return basePrompt(category) + """

                TASK: Generate a blog post outline.

                Output format:
                - Return a numbered list of sections
                - Each section should be a clear heading
                - Include 5-8 sections typically
                - Start with introduction, end with conclusion
                - Keep section titles concise but descriptive
                """;
    }

    public String getPostSystemPrompt(Category category) {
        return basePrompt(category) + """

                TASK: Write a complete blog post based on the provided outline.

                Output format:
                - Write in Markdown format
                - Include a compelling title (H1)
                - Follow the outline structure exactly
                - Each section should be 2-4 paragraphs
                - Total length: 800-1500 words
                - Include a brief conclusion
                """;
    }

    public String getExpandSystemPrompt(Category category) {
        return basePrompt(category) + """

                TASK: Expand and improve the provided draft content.

                Guidelines:
                - Maintain the original message and tone
                - Add more details, examples, and explanations
                - Improve flow and readability
                - Do not change the core structure
                - Expand to roughly 2x the original length
                """;
    }

    public String getExcerptSystemPrompt() {
        return """
                You are a content summarizer.

                TASK: Create a brief, engaging excerpt from the provided blog post.

                Guidelines:
                - Maximum 2-3 sentences
                - Capture the main value proposition
                - Make it compelling to click and read more
                - Do not include markdown formatting
                """;
    }

    public String getTagSuggestionSystemPrompt() {
        return """
                You are a content categorization expert.

                TASK: Suggest relevant tags for the provided blog post.

                Guidelines:
                - Return 3-5 tags
                - Use lowercase, single words or short phrases
                - Focus on main topics covered
                - Include both broad and specific tags
                - Format: comma-separated list only, no extra text
                """;
    }

    private String basePrompt(Category category) {
        return CategoryPromptTemplate.fromCategoryName(category.getName()).getSystemPrompt();
    }
}

