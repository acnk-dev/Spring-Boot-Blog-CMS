# Commit 50 — AIService: Expand, Excerpt & Tag Suggestion

## Overview

This commit completes the `AIService` by adding three content-enhancement operations:

- **`expandContent`** — takes a draft section (or full post) and a category, and asks Gemini
  to grow it to roughly 2× its length while preserving the original tone and structure
- **`generateExcerpt`** — summarises a full post into a 2–3 sentence excerpt suitable for
  listing pages and meta descriptions
- **`suggestTags`** — reads the post content and returns a normalised list of relevant tags

Additionally, `generatePost` (commit 49) is updated to call `generateExcerpt` and
`suggestTags` automatically after generating the post body — replacing the empty stubs
from the previous commit.

---

## Problem / Motivation

Commit 49 left `excerpt` and `suggestedTags` in `GeneratePostResponse` as empty stubs. This
was intentional — the DTOs were established early so the response shape would not change
between commits. Now those fields need to return real AI-generated values.

Beyond filling the stubs, content expansion and standalone excerpt/tag endpoints are
independently useful features:
- An author who writes their own draft can still request AI expansion of a weak section
- A post imported from another system can get AI-generated tags and an excerpt without
  going through the full outline → post pipeline

---

## Decision

### Should `generatePost` call `generateExcerpt` and `suggestTags` internally?

Yes — and this is a deliberate design choice. A caller invoking `POST /api/v1/ai/post`
expects a complete, ready-to-use result. Requiring a second and third API call to get the
excerpt and tags would be poor UX. The extra two AI calls add ~2–4 seconds to post
generation, which is acceptable given that post generation itself already takes 5–15 seconds.

The alternative (returning the post body only and letting the caller request excerpt/tags
separately) would be appropriate only if callers frequently wanted the post without
excerpt/tags — which is not the case here.

### Why does `generateExcerpt` take no `Category` parameter?

Excerpt generation is content-agnostic. The AI reads the full post text and distills it
into 2–3 sentences regardless of whether the post is about Redis or sourdough bread. Passing
a category would add complexity with no benefit — the excerpt persona ("content summariser")
does not change by category.

This is why `getExcerptSystemPrompt()` and `getTagSuggestionSystemPrompt()` in
`PromptTemplateService` take no arguments (established in commit 48).

### Why normalise tags to lowercase but not deduplicate against the database?

`AIServiceImpl` is a pure AI orchestration layer — it sends prompts and parses responses.
Deduplication against existing DB tags is a business rule that belongs in `TagService`.
If `suggestTags` returns `["redis", "caching"]` and "redis" already exists as a tag in
the DB, `TagService` should decide whether to reuse the existing tag or create a new one.
Keeping `AIServiceImpl` free of repository calls (other than `CategoryRepository` for
prompt selection) maintains clean separation of concerns.

---

## Changes Made

### `dto/ai/ExpandContentRequest.java` (new)

```java
public class ExpandContentRequest {
    @NotBlank  private String content;    // the draft to expand
    @NotNull   private Long categoryId;   // determines the AI writing persona
               private String focusAreas; // optional: "add more code examples"
}
```

`categoryId` is required because the expand prompt is category-aware — a Technology post
expands with code examples; a Food post expands with sensory language.

---

### `dto/ai/ExpandContentResponse.java` (new)

```java
@Getter @Builder
public class ExpandContentResponse {
    private String expandedContent;
    private int originalWordCount;
    private int expandedWordCount;
    private String generatedAt;
}
```

Word counts give the author immediate feedback on how much the content grew. Computed by
`countWords()` — a whitespace split that works well for blog prose.

---

### `dto/ai/ExcerptRequest.java` (new)

```java
public class ExcerptRequest {
    @NotBlank private String content;
              private int maxLength;  // defaults to 200 if 0 or not provided
}
```

`maxLength` defaults to 200 characters — appropriate for a meta description. The caller can
override it (e.g. 300 for a card preview, 150 for a tweet-length excerpt).

---

### `dto/ai/TagSuggestionRequest.java` (new)

```java
public class TagSuggestionRequest {
    @NotBlank private String content;
              private int maxTags;  // defaults to 5 if 0 or not provided
}
```

---

### `service/AIService.java` (updated)

Three new methods added to the interface:

```java
ExpandContentResponse expandContent(ExpandContentRequest request);
String generateExcerpt(ExcerptRequest request);
List<String> suggestTags(TagSuggestionRequest request);
```

Note the return types: `expandContent` returns a rich DTO (callers need the word counts);
`generateExcerpt` returns a plain `String` (there is nothing to wrap); `suggestTags` returns
`List<String>` (the parsed, normalised tag list).

---

### `service/impl/AIServiceImpl.java` (updated)

**`expandContent` implementation:**

```java
@Override
public ExpandContentResponse expandContent(ExpandContentRequest request) {
    Category category = findCategory(request.getCategoryId());

    StringBuilder userPrompt = new StringBuilder("Expand and improve the following content:\n\n");
    userPrompt.append(request.getContent());
    if (request.getFocusAreas() != null && !request.getFocusAreas().isBlank()) {
        userPrompt.append("\n\nFocus on: ").append(request.getFocusAreas());
    }

    String expandedContent = chatClient.prompt()
            .system(promptTemplateService.getExpandSystemPrompt(category))
            .user(userPrompt.toString())
            .call()
            .content();

    return ExpandContentResponse.builder()
            .expandedContent(expandedContent)
            .originalWordCount(countWords(request.getContent()))
            .expandedWordCount(countWords(expandedContent))
            .generatedAt(LocalDateTime.now().toString())
            .build();
}
```

**`generateExcerpt` implementation:**

```java
@Override
public String generateExcerpt(ExcerptRequest request) {
    int maxLength = request.getMaxLength() > 0 ? request.getMaxLength() : 200;
    String userPrompt = String.format(
            "Create an excerpt (max %d characters) for this blog post:\n\n%s",
            maxLength, request.getContent());

    return chatClient.prompt()
            .system(promptTemplateService.getExcerptSystemPrompt())
            .user(userPrompt)
            .call()
            .content()
            .trim();
}
```

`.trim()` removes any leading/trailing whitespace the model adds around its response.

**`suggestTags` implementation:**

```java
@Override
public List<String> suggestTags(TagSuggestionRequest request) {
    int maxTags = request.getMaxTags() > 0 ? request.getMaxTags() : 5;
    String userPrompt = String.format(
            "Suggest %d relevant tags for this blog post:\n\n%s",
            maxTags, request.getContent());

    String response = chatClient.prompt()
            .system(promptTemplateService.getTagSuggestionSystemPrompt())
            .user(userPrompt)
            .call()
            .content();

    return Arrays.stream(response.split(","))
            .map(String::trim)
            .map(String::toLowerCase)
            .filter(tag -> !tag.isEmpty())
            .limit(maxTags)
            .toList();
}
```

The tag parsing pipeline: split on comma → trim whitespace → lowercase → remove empties →
cap at maxTags. Each step is a guard against a slightly different model output variation.

**`generatePost` updated to fill stubs:**

```java
// Before (commit 49) — stubs
return GeneratePostResponse.builder()
        .excerpt("")
        .suggestedTags(List.of())
        ...

// After (commit 50) — real AI calls
ExcerptRequest excerptRequest = new ExcerptRequest();
excerptRequest.setContent(content);
String excerpt = generateExcerpt(excerptRequest);

TagSuggestionRequest tagRequest = new TagSuggestionRequest();
tagRequest.setContent(content);
List<String> tags = suggestTags(tagRequest);

return GeneratePostResponse.builder()
        .excerpt(excerpt)
        .suggestedTags(tags)
        ...
```

**Private helper extracted:**

```java
private Category findCategory(Long categoryId) {
    return categoryRepository.findById(categoryId)
            .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + categoryId));
}
```

This deduplicates the `categoryRepository.findById(...).orElseThrow(...)` pattern that
appeared in both `generateOutline` and `generatePost` (commit 49) and is now also needed
in `expandContent`.

---

### `docs/tutorial/commit-47-spring-ai-tutorial.md` (updated)

Parts 11–13 appended:

- **Part 11 — Content Enhancement: Expand**: use case, how `expandContent` works, word count
  tracking, maintaining tone via category-aware system prompt
- **Part 12 — Excerpt Generation**: why auto-generate excerpts, `maxLength` defaulting,
  integration with `generatePost`
- **Part 13 — Tag Suggestion**: AI-powered categorisation, parsing comma-separated responses,
  normalisation steps, why deduplication belongs in `TagService` not here

---

## Concepts Introduced

### Defensive default values for optional numeric fields

`maxLength` and `maxTags` are `int` fields (primitives) in their request DTOs. Java
initialises `int` fields to `0` when not provided in the JSON body. The implementation
converts `0` to a sensible default:

```java
int maxLength = request.getMaxLength() > 0 ? request.getMaxLength() : 200;
int maxTags   = request.getMaxTags()   > 0 ? request.getMaxTags()   : 5;
```

This is simpler than using `Integer` (boxed) and checking for `null`, and avoids the case
where a caller accidentally sends `maxTags: 0` and gets zero tags back.

---

### Chaining AI calls inside a single service method

`generatePost` now makes three sequential AI calls:

```
1. chatClient.prompt()...call() → full post content   (~5–15s)
2. generateExcerpt(...)         → excerpt             (~1–2s)
3. suggestTags(...)             → tags                (~1–2s)
```

These run sequentially (not in parallel) because they are simple to implement and the total
time (~7–19s) is acceptable for a content generation use case. In a high-traffic production
system you could run calls 2 and 3 concurrently using `CompletableFuture`:

```java
CompletableFuture<String> excerptFuture = CompletableFuture.supplyAsync(
        () -> generateExcerpt(excerptRequest));
CompletableFuture<List<String>> tagsFuture = CompletableFuture.supplyAsync(
        () -> suggestTags(tagRequest));

String excerpt = excerptFuture.join();
List<String> tags = tagsFuture.join();
```

For this teaching project, sequential calls are clearer and sufficient.

---

### Tag parsing pipeline

The model returns `"redis, caching, spring-boot, java, performance"`. The pipeline
processes this string into a clean `List<String>`:

```java
Arrays.stream(response.split(","))  // ["redis", " caching", " spring-boot", ...]
    .map(String::trim)              // ["redis", "caching", "spring-boot", ...]
    .map(String::toLowerCase)       // guards against "Redis" vs "redis"
    .filter(tag -> !tag.isEmpty())  // removes empty strings from trailing commas
    .limit(maxTags)                 // caps at the requested max
    .toList();                      // returns an immutable List<String>
```

Each step handles one specific failure mode. Removing any step would cause a different class
of bug under slightly different model output.

---

## Testing Checklist

- [ ] `POST /api/v1/ai/post` now returns non-empty `excerpt` and `suggestedTags` (stubs gone)
- [ ] `suggestedTags` items are lowercase and trimmed (no `" Redis"` or `"Redis"`)
- [ ] `POST /api/v1/ai/expand` with `content` + valid `categoryId` returns `expandedContent`
  with `expandedWordCount` > `originalWordCount`
- [ ] `POST /api/v1/ai/expand` with `focusAreas: "add more code examples"` produces output
  with additional code examples
- [ ] `POST /api/v1/ai/excerpt` returns a plain string of 2–3 sentences with no Markdown
- [ ] `POST /api/v1/ai/excerpt` with `maxLength: 100` produces a shorter excerpt than
  with `maxLength: 300`
- [ ] `POST /api/v1/ai/suggest-tags` with a Technology post returns tags like
  `["java", "spring-boot", "redis"]` — all lowercase, no numbers/symbols
- [ ] `POST /api/v1/ai/expand` with missing `categoryId` returns `400`
- [ ] `POST /api/v1/ai/expand` with an invalid `categoryId` returns `404`
- [ ] Maven build passes: `./mvnw test`
