# Commit 51 — Multi-Agent Image Generation with Imagen 3

## Overview

This commit introduces AI-powered featured image generation using a two-agent pipeline:

- **Agent 1 — `ImagePromptAgent`**: Uses the existing `ChatClient` (Gemini via
  OpenAI-compatible endpoint) to translate a blog title and category into a
  detailed, Imagen-3-friendly image description prompt.
- **Agent 2 — `ImageGenerationServiceImpl`**: Orchestrates Agent 1, then calls
  Google's Imagen 3 REST API directly, decodes the base64-encoded PNG response,
  and saves the image bytes to `uploads/ai-images/`.

A property toggle (`ai.image.enabled`) controls whether Agent 2 actually calls Imagen 3
or returns a placeholder URL. Agent 1 always runs — the generated prompt is always
returned in the response regardless of whether image generation is enabled.

---

## Problem / Motivation

Blog featured images have a significant impact on click-through rates, but most authors
either skip them, use stock photos, or spend time searching for the right image. An AI
pipeline can generate a relevant, on-brand image from the blog title alone in seconds.

The challenge is that image generation requires a different model from text generation:
- **Text generation** (outline, post, excerpt, tags) → Gemini via OpenAI-compatible endpoint
- **Image generation** → Imagen 3 via Google AI native REST API

These two models require different API calls, different request formats, and return different
response types. The multi-agent pattern cleanly separates these concerns: Agent 1 uses
what we already have (ChatClient/Gemini), and Agent 2 adds the new Imagen 3 integration.

---

## Decision

### Why not use Spring AI's `ImageModel` abstraction?

Spring AI has an `ImageModel` interface designed to abstract image generation providers.
However, both available implementations require infrastructure we cannot use here:

| Implementation | Requirement | Problem |
|---|---|---|
| `OpenAiImageModel` (DALL-E) | OpenAI API key | We only have a Gemini key |
| `VertexAiImageModel` (Imagen) | GCP project + ADC | Requires `gcloud` CLI setup |

Google AI Studio's Imagen 3 endpoint (`generativelanguage.googleapis.com`) accepts the
same `GEMINI_API_KEY` we already have and requires no GCP infrastructure. Calling it
directly with `RestClient` was the only option that works with our existing setup.

### Why a separate `ImagePromptAgent` instead of hardcoding the prompt in the service?

Image prompt engineering is a distinct skill from image generation. The agent:
1. Has its own focused system prompt tuned for Imagen-3-friendly output
2. Can be tested independently by calling it directly
3. Can be swapped for a different model or a manual prompt in the future without touching
   `ImageGenerationServiceImpl`
4. Demonstrates the "AI helping AI" pattern — one model preparing input for another

If the image prompt logic were inline in `ImageGenerationServiceImpl`, changing the prompt
strategy would require modifying the orchestration class.

### Why `Files.write()` instead of `FileStorageService.storeFile()`?

`FileStorageService.storeFile()` accepts `MultipartFile` — a Spring abstraction for
HTTP file uploads that wraps an `InputStream`, content type, and size metadata. Imagen 3
returns raw `byte[]`. Converting `byte[]` to a `MultipartFile` is possible via
`MockMultipartFile` (a test utility) or a custom wrapper, but both approaches are
awkward workarounds. `Files.write()` writes bytes to disk cleanly and directly — the right
tool for the job. The path construction and directory conventions match what
`LocalFileStorageServiceImpl` uses.

### `ai.image.enabled` toggle

The toggle exists for two reasons:
1. **Development** — developers without Imagen 3 quota can still test the full pipeline
   flow. Agent 1 runs (real Gemini call), the generated prompt appears in the response,
   only the Imagen 3 call is skipped.
2. **Cost control** — image generation costs more than text generation. Teams can disable
   it per environment.

---

## Changes Made

### `dto/ai/GenerateImageRequest.java` (new)

```java
public class GenerateImageRequest {
    @NotBlank  private String title;       // blog post title
    @NotNull   private Long categoryId;    // determines the visual style direction
               private String style;       // optional: "minimalist", "photorealistic"
               private String additionalContext; // optional: extra context for Agent 1
}
```

---

### `dto/ai/GenerateImageResponse.java` (new)

```java
@Getter @Builder
public class GenerateImageResponse {
    private String imageUrl;        // relative URL to the saved image (or placeholder)
    private String generatedPrompt; // the Agent 1 output — always present
    private String generatedAt;
}
```

`generatedPrompt` is always populated — even when `ai.image.enabled=false`. This lets
callers inspect what prompt would have been used, and allows manual generation using that
prompt in an external tool.

---

### `ai/ImagePromptAgent.java` (new)

```java
@Component
@RequiredArgsConstructor
public class ImagePromptAgent {

    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = """
            You are an expert at creating image generation prompts for blog featured images.
            Guidelines:
            - Describe visual elements clearly (objects, scene, lighting, colors)
            - Include the artistic style
            - Avoid text or words in the image
            - Keep it professional and appropriate for a blog
            Output: Return only the image prompt — no preamble, no explanation.
            """;

    public String generateImagePrompt(String title, String categoryName, String style) {
        String userPrompt = String.format("""
                Blog Title: %s
                Category: %s
                Preferred Style: %s
                Generate a detailed prompt for the featured image.
                """, title, categoryName, style != null ? style : "modern and professional");

        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content()
                .trim();
    }
}
```

Key prompt decisions:
- **"Return only the image prompt — no preamble"** prevents Gemini from adding
  "Sure! Here is a prompt for you:" before the actual content, which would corrupt
  Imagen 3's input.
- **"Avoid text or words in the image"** — image models consistently produce garbled,
  unreadable text. Excluding text steers the model toward purely visual compositions.
- **`.trim()`** strips any leading/trailing whitespace or newlines the model adds.

---

### `service/ImageGenerationService.java` (new)

```java
public interface ImageGenerationService {
    GenerateImageResponse generateFeaturedImage(GenerateImageRequest request);
}
```

---

### `service/impl/ImageGenerationServiceImpl.java` (new)

The orchestrator. Key sections:

**Initialisation:**
```java
@PostConstruct
public void init() {
    restClient = RestClient.create();
    aiImagesDir = Paths.get(fileStorageConfig.getUploadDir())
            .toAbsolutePath().normalize().resolve("ai-images");
    Files.createDirectories(aiImagesDir);
}
```

`RestClient.create()` produces a default instance with Jackson for JSON serialisation.
`aiImagesDir` is resolved from the same `file.upload-dir` property used by
`LocalFileStorageServiceImpl` — keeping the directory convention consistent.

**Orchestration:**
```java
// Agent 1 — always runs
String imagePrompt = imagePromptAgent.generateImagePrompt(
        request.getTitle(), category.getName(), request.getStyle());

if (!imageEnabled) {
    return GenerateImageResponse.builder()
            .imageUrl(PLACEHOLDER_URL)
            .generatedPrompt(imagePrompt)   // prompt still returned
            .generatedAt(LocalDateTime.now().toString())
            .build();
}

// Agent 2 — conditional on ai.image.enabled
String imageUrl = callImagenAndSave(imagePrompt);
```

**Imagen 3 REST call:**
```java
Map<String, Object> requestBody = Map.of(
    "instances", List.of(Map.of("prompt", imagePrompt)),
    "parameters", Map.of("sampleCount", 1, "aspectRatio", "16:9")
);

Map<String, Object> response = restClient.post()
        .uri(IMAGEN_BASE_URL)
        .header("x-goog-api-key", geminiApiKey)
        .header("Content-Type", "application/json")
        .body(requestBody)
        .retrieve()
        .body(Map.class);
```

**Decoding and saving:**
```java
String base64Image = (String) predictions.get(0).get("bytesBase64Encoded");
byte[] imageBytes = Base64.getDecoder().decode(base64Image);

String fileName = "ai-generated-" + UUID.randomUUID() + ".png";
Files.write(aiImagesDir.resolve(fileName), imageBytes);

return "/uploads/ai-images/" + fileName;
```

---

### `application.properties` (updated)

```properties
# Image Generation (Imagen 3 via Google AI native API)
# Set to false to skip actual image generation and return a placeholder URL.
# Agent 1 (ImagePromptAgent) always runs — only the Imagen 3 call is skipped.
ai.image.enabled=true
```

---

### `docs/tutorial/commit-47-spring-ai-tutorial.md` (updated)

Parts 14–17 appended:
- **Part 14 — Multi-Agent Architecture**: what multi-agent AI is, when to use it, how agent
  communication works (output of one = input of next)
- **Part 15 — Image Prompt Agent**: why a separate agent for prompt generation, the system
  prompt design choices, how the style parameter works
- **Part 16 — Imagen 3 Integration**: why not Spring AI's `ImageModel`, `RestClient` fluent
  API, base64 decoding, saving image bytes, the `ai.image.enabled` toggle
- **Part 17 — Orchestrating Agents**: the orchestrator's responsibilities, logging strategy
  for multi-agent flows, separation so agents don't know about each other

---

## Concepts Introduced

### RestClient — Spring's modern HTTP client

`RestClient` (Spring 6.1+) is a fluent, synchronous HTTP client that replaces `RestTemplate`:

```java
// RestTemplate (old, verbose)
ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

// RestClient (modern, fluent)
Map<String, Object> response = restClient.post()
        .uri(url)
        .header("x-goog-api-key", apiKey)
        .body(requestBody)
        .retrieve()
        .body(Map.class);
```

`RestClient` reads more like the request it represents. `.retrieve()` executes the call
and throws `HttpClientErrorException` / `HttpServerErrorException` on 4xx/5xx responses
automatically — no manual status checking needed for standard cases.

---

### Base64 image decoding

AI image APIs return images as base64-encoded strings to avoid binary transfer issues in
JSON. Decoding is one line:

```java
byte[] imageBytes = Base64.getDecoder().decode(base64String);
```

`java.util.Base64.getDecoder()` uses standard Base64 encoding (RFC 4648). The result is
the raw PNG bytes ready for `Files.write()`. For very large images (>10 MB), stream
decoding is safer — but at 1200×630px blog images are typically 100–500 KB.

---

### The "AI helping AI" pattern

`ImagePromptAgent` uses a text model (Gemini) to prepare better input for an image model
(Imagen 3). This pattern appears frequently in production AI systems:

- A summariser prepares a dense context before a reasoning model reads it
- A classifier routes input to the right specialist model
- A prompt engineer (like our `ImagePromptAgent`) translates human intent into model-optimal
  instructions

The key insight is that each model has a different "language" — what works as a prompt for
a chat model is not what works as a prompt for an image model. An intermediate agent bridges
the gap.

---

## Testing Checklist

- [ ] App starts without errors — `@PostConstruct` creates `uploads/ai-images/` directory
- [ ] With `ai.image.enabled=false`: `POST /api/v1/ai/generate-image` returns
  `{ "imageUrl": "/images/placeholder-featured.png", "generatedPrompt": "<real AI prompt>", ... }`
- [ ] `generatedPrompt` in the response is a non-empty descriptive string (Agent 1 ran)
- [ ] With `ai.image.enabled=true` and valid `GEMINI_API_KEY`: response contains a real
  `/uploads/ai-images/ai-generated-<uuid>.png` URL
- [ ] The generated PNG file exists on disk at `uploads/ai-images/ai-generated-<uuid>.png`
- [ ] Different categories produce noticeably different image prompts
  (Technology → data/tech aesthetic; Food → warm/culinary aesthetic)
- [ ] `style: "watercolor illustration"` in the request changes the generated prompt style
- [ ] `POST /api/v1/ai/generate-image` with invalid `categoryId` returns `404`
- [ ] `POST /api/v1/ai/generate-image` with missing `title` returns `400`
- [ ] Maven build passes: `./mvnw test`
