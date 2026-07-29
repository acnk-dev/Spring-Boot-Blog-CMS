package com.aditya_nk.blogcms.util;

import com.aditya_nk.blogcms.repository.PostRepository;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Utility class for generating URL-friendly slugs.
 *
 * A slug is a URL-friendly version of a string, typically used for:
 * - Blog post URLs: /posts/getting-started-with-spring-boot
 * - Category URLs: /categories/technology
 * - Tag URLs: /tags/java
 *
 * Slug rules:
 * - Lowercase
 * - Spaces replaced with hyphens
 * - Special characters removed
 * - Multiple hyphens collapsed to single
 * - No leading/trailing hyphens
 */
public class SlugUtil {

    private static final Pattern NON_LATIN = Pattern.compile("[^\\w-]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s]");
    private static final Pattern MULTIPLE_HYPHENS = Pattern.compile("-+");

    /**
     * Generate a URL-friendly slug from a title.
     *
     * Examples:
     * - "Hello World" → "hello-world"
     * - "Getting Started with Spring Boot!" → "getting-started-with-spring-boot"
     * - "C++ Programming" → "c-programming"
     * - "  Multiple   Spaces  " → "multiple-spaces"
     *
     * @param title the title to convert
     * @return URL-friendly slug
     */
    public static String generateSlug(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }

        String normalized = Normalizer.normalize(title, Normalizer.Form.NFD);
        String slug = WHITESPACE.matcher(normalized).replaceAll("-");
        slug = NON_LATIN.matcher(slug).replaceAll("");
        slug = MULTIPLE_HYPHENS.matcher(slug).replaceAll("-");
        slug = slug.toLowerCase(Locale.ENGLISH);
        slug = slug.replaceAll("^-|-$", ""); // Remove leading/trailing hyphens

        return slug;
    }

    /**
     * Generate a unique slug by appending a number if the base slug exists.
     *
     * Examples:
     * - "hello-world" (if doesn't exist) → "hello-world"
     * - "hello-world" (if exists) → "hello-world-1"
     * - "hello-world" (if -1 exists) → "hello-world-2"
     *
     * @param title the title to convert
     * @param repository the PostRepository to check for existing slugs
     * @return unique URL-friendly slug
     */
    public static String generateUniqueSlug(String title, PostRepository repository) {
        String baseSlug = generateSlug(title);

        if (baseSlug.isEmpty()) {
            baseSlug = "post";
        }

        String slug = baseSlug;
        int counter = 1;

        while (repository.existsBySlug(slug)) {
            slug = baseSlug + "-" + counter;
            counter++;
        }

        return slug;
    }

    /**
     * Generate a unique slug, checking against existing slug.
     * Used for updates where the post may keep its own slug.
     *
     * @param title the title to convert
     * @param currentSlug the current slug of the post (to exclude from uniqueness check)
     * @param repository the PostRepository to check for existing slugs
     * @return unique URL-friendly slug
     */
    public static String generateUniqueSlug(String title, String currentSlug, PostRepository repository) {
        String baseSlug = generateSlug(title);

        if (baseSlug.isEmpty()) {
            baseSlug = "post";
        }

        // If the generated slug is the same as current, keep it
        if (baseSlug.equals(currentSlug)) {
            return currentSlug;
        }

        String slug = baseSlug;
        int counter = 1;

        while (repository.existsBySlug(slug) && !slug.equals(currentSlug)) {
            slug = baseSlug + "-" + counter;
            counter++;
        }

        return slug;
    }
}
