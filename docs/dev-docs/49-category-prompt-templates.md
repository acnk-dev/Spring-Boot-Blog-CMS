# Commit 48 — Category-based Prompt Template System

## Overview

This commit introduces the prompt engineering layer that sits between the `ChatClient` and all
future AI features. Instead of hardcoding system prompts inside each service method, we now
have a dedicated `ai/` package with two classes:

- `CategoryPromptTemplate` — an enum that maps a blog category name to a tailored AI persona
- `PromptTemplateService` — a Spring service that composes the full system prompt for each
  specific AI task (outline, post, expand, excerpt, tags)

Every AI call from commit 56 onwards will call `PromptTemplateService` to get its system
prompt, ensuring consistent tone and format across all features without duplicating prompt text.

---

## Problem / Motivation

Different blog categories require fundamentally different writing styles:

- A **Technology** post benefits from code examples, precise terminology, and developer-level depth
- A **Food** post benefits from sensory language, ingredient lists, and an enthusiastic tone
- A **Finance** post requires disclaimers, cautious language, and number-based examples

Without category-aware prompts, every AI call would use a generic "professional writer" persona
and produce mediocre output for all categories. More importantly, each future AI service method
would need to define its own system prompt inline — duplicating the category logic everywhere
and making it hard to improve prompts consistently.

The solution is a single source of truth for prompts: `CategoryPromptTemplate` owns *who the AI
is* and `PromptTemplateService` appends *what the AI should do right now*.

---

## Decision

### Option 1 — Inline prompts per service method

Each service method (e.g. `generateOutline`, `generatePost`) builds the full system prompt
from scratch inline. Simple, but:
- Duplicates category-role text in every method
- Updating a category persona requires finding all scattered usages
- No central place to review or test all prompts

### Option 2 — Single `PromptTemplateService` with a task enum

One method per task, keyed by a `TaskType` enum (`OUTLINE`, `POST`, `EXPAND`…). Centralised,
but the task enum introduces a switch statement that grows with every new feature.

### Option 3 (chosen) — Enum for persona + service for task composition

`CategoryPromptTemplate` is a Java enum where each constant holds a multi-line system prompt
string. `PromptTemplateService` has one method per task; each method calls
`CategoryPromptTemplate.fromCategoryName()` to get the base persona and appends a task
instruction block.

**Why this wins:**
- Persona and task instruction are separated — each can evolve independently
- Adding a new category = one new enum constant, zero other changes
- Adding a new task = one new method in `PromptTemplateService`, zero enum changes
- All prompts are in one package and easy to review together

### Why `getExcerptSystemPrompt` and `getTagSuggestionSystemPrompt` take no `Category`

Excerpt and tag generation operate on the *content itself*, not the topic. The AI reads the
full post text and derives a summary or tags regardless of category. Forcing a food-writer
persona onto a tag-suggestion task would produce no benefit — so these two methods use a
fixed, content-agnostic system prompt.

---

## Changes Made

### `src/main/java/com/studylink/blogcms/ai/CategoryPromptTemplate.java` (new)

An enum with six constants. Each constant is constructed with a text-block system prompt
that defines role, guidelines, format, tone, and audience.

```java
public enum CategoryPromptTemplate {

    TECHNOLOGY("""
            You are a technical content writer specializing in software development.
            Guidelines:
            - Use code examples where relevant
            - Explain concepts clearly for developers
            ...
            """),

    FOOD("""
            You are a culinary writer and food critic.
            Guidelines:
            - Focus on flavors, textures, and aromas
            ...
            """),

    DEFAULT("""
            You are a professional content writer.
            ...
            """);

    private final String systemPrompt;

    // Constructor + getSystemPrompt() omitted for brevity

    public static CategoryPromptTemplate fromCategoryName(String categoryName) {
        if (categoryName == null) return DEFAULT;
        String upper = categoryName.trim().toUpperCase();
        for (CategoryPromptTemplate template : values()) {
            if (template.name().equals(upper)) return template;
        }
        return DEFAULT;  // unknown categories fall back gracefully
    }
}
```

Key design decisions:
- **Text blocks (`"""..."""`)** — Java 15+ feature that preserves indentation and avoids
  escaped newlines. Far more readable than concatenated strings.
- **`fromCategoryName` fallback to DEFAULT** — unknown or null category names never throw;
  the AI just uses a generic professional writer persona. This means new categories in the
  DB are safe before anyone updates the enum.
- **Enum names match DB category names** — `TECHNOLOGY` matches a `Category` with
  `name = "Technology"` (comparison is case-insensitive via `.toUpperCase()`).

---

### `src/main/java/com/studylink/blogcms/ai/PromptTemplateService.java` (new)

A `@Service` with five public methods. Each method composes the full system prompt by
combining the category base prompt with a task-specific instruction block:

```java
@Service
public class PromptTemplateService {

    public String getOutlineSystemPrompt(Category category) {
        return basePrompt(category) + """

                TASK: Generate a blog post outline.
                Output format:
                - Return a numbered list of sections
                - Include 5-8 sections
                - Start with introduction, end with conclusion
                """;
    }

    public String getExcerptSystemPrompt() {  // no Category — content-agnostic
        return """
                You are a content summarizer.
                TASK: Create a brief, engaging excerpt.
                - Maximum 2-3 sentences
                - No markdown formatting
                """;
    }

    private String basePrompt(Category category) {
        return CategoryPromptTemplate.fromCategoryName(category.getName()).getSystemPrompt();
    }
}
```

Why separate methods instead of one method with a task parameter?

Each task has different output format requirements. `getOutlineSystemPrompt` instructs
"return a numbered list." `getTagSuggestionSystemPrompt` instructs "return a comma-separated
list only, no extra text." These format instructions directly affect how the calling service
parses the AI response. Putting them in separate methods makes each task's contract explicit
and eliminates a switch statement that would need updating with every new AI feature.

---

### `docs/tutorial/commit-47-spring-ai-tutorial.md` (updated)

Parts 5–7 appended:

- **Part 5 — Prompt Engineering Basics**: system vs user prompt, why system prompts matter,
  the four components of a good system prompt, temperature tuning
- **Part 6 — Category-based Prompts**: why different categories need different styles,
  how `CategoryPromptTemplate` and `PromptTemplateService` work together, how to extend
  for new categories, how to test prompt quality
- **Part 7 — Prompt Best Practices**: be specific, specify output format, provide examples,
  set constraints, iterate based on results

---

## Concepts Introduced

### Java Text Blocks (`"""..."""`)

Text blocks are a Java 15 feature (standard in Java 21) for writing multi-line string literals
without escape sequences:

```java
// Before text blocks — hard to read, easy to make mistakes
String prompt = "You are a writer.\n\nGuidelines:\n- Be clear\n- Use examples\n";

// With text blocks — reads like the actual output
String prompt = """
        You are a writer.

        Guidelines:
        - Be clear
        - Use examples
        """;
```

The compiler strips the common leading whitespace (the indentation of the closing `"""`),
so the string does not have leading spaces. Text blocks are ideal for prompts, SQL, HTML,
and JSON literals in code.

---

### Enum as configuration holder

Java enums can hold state (fields) and behaviour (methods), not just names. This pattern —
an enum where each constant carries a configuration value — is common in Spring applications:

```java
public enum CategoryPromptTemplate {
    TECHNOLOGY("...tech prompt..."),
    FOOD("...food prompt...");

    private final String systemPrompt;  // field

    CategoryPromptTemplate(String systemPrompt) {  // constructor
        this.systemPrompt = systemPrompt;
    }

    public String getSystemPrompt() { return systemPrompt; }  // method
}
```

The enum guarantees at compile time that every category has a prompt. Adding a category
without a prompt is a compile error, not a runtime null.

---

### Prompt composition pattern

The system prompt used in each AI call is the result of two parts joined together:

```
[Category persona — who the AI is]
+
[Task instruction — what the AI should do right now]
= Full system prompt
```

This is similar to the **Template Method** design pattern: the base defines the invariant
parts (persona, guidelines, tone), and each subclass (task method) provides the variable
part (specific instructions and output format). The model reads both parts together as a
single coherent instruction set.

---

## Testing Checklist

- [ ] App starts without errors after adding the `ai/` package
- [ ] `PromptTemplateService` is injectable — add `@Autowired PromptTemplateService pts` to
  a test and call `pts.getOutlineSystemPrompt(category)` with a Technology category; verify
  the returned string contains "technical content writer" and "numbered list"
- [ ] `CategoryPromptTemplate.fromCategoryName("Technology")` returns `TECHNOLOGY`
- [ ] `CategoryPromptTemplate.fromCategoryName("technology")` returns `TECHNOLOGY`
  (case-insensitive)
- [ ] `CategoryPromptTemplate.fromCategoryName("Unknown")` returns `DEFAULT`
- [ ] `CategoryPromptTemplate.fromCategoryName(null)` returns `DEFAULT` without throwing
- [ ] `getExcerptSystemPrompt()` and `getTagSuggestionSystemPrompt()` compile and return
  non-empty strings (they take no Category argument)
- [ ] Maven build passes: `./mvnw test`
