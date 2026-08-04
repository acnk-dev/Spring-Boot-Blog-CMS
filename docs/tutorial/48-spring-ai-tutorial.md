# Spring AI Tutorial — Blog CMS Week 7

This tutorial walks you through integrating AI into a real Spring Boot 4.x application, step by step.
Each part maps to a commit in the project so you can follow the code as you read.

---

## Part 1: Introduction to Spring AI

### What is Spring AI?

Spring AI is an official Spring project that provides a portable, vendor-neutral abstraction over
large language model (LLM) providers. Instead of writing provider-specific HTTP calls, you work
with consistent interfaces — `ChatClient`, `ChatModel`, `ImageModel` — and swap providers by
changing a dependency and a few properties.

Think of it like Spring Data: you write `repository.save(entity)` and Spring Data handles whether
the underlying store is PostgreSQL, MongoDB, or Redis. Spring AI does the same for AI providers.

### Why use Spring AI instead of calling the API directly?

| Direct API call | Spring AI |
|---|---|
| Manual HTTP client setup | Auto-configured beans |
| Provider-specific request/response models | Unified `Prompt` / `ChatResponse` types |
| Re-implement retry, timeout, error handling | Built into the framework |
| Switch providers = rewrite code | Switch providers = change dependency + properties |
| No Spring integration | Full integration with `@Configuration`, `@Bean`, profiles |

For a teaching project, Spring AI also means less boilerplate — you focus on the feature, not the
HTTP plumbing.

### Supported providers

Spring AI supports (among others):
- **Google Gemini** — via direct API key (what we use) or Vertex AI
- **OpenAI** (GPT-4o, GPT-4, etc.)
- **Anthropic** (Claude)
- **Ollama** — run open-source models locally (Llama 3, Mistral, etc.)
- **Azure OpenAI**, **Amazon Bedrock**, **Mistral AI**

Switching between them is a one-line dependency change — the application code stays the same.

### Key abstractions

| Abstraction | What it does |
|---|---|
| `ChatClient` | High-level fluent API — the main entry point you'll use day-to-day |
| `ChatModel` | Low-level interface `ChatClient` delegates to — rarely used directly |
| `Prompt` | Wraps one or more `Message` objects (system + user + assistant turns) |
| `ChatResponse` | Holds the model's reply plus metadata (tokens used, finish reason, etc.) |
| `ChatClient.Builder` | Auto-configured by Spring Boot — inject it to build a `ChatClient` |

---

## Part 2: Google Gemini Setup

### Why not Vertex AI?

Google offers two ways to call Gemini:

| | Google AI Studio | Vertex AI |
|---|---|---|
| Auth | API key (env var) | GCP project + `gcloud` CLI + Application Default Credentials |
| Setup time | 2 minutes | 30+ minutes |
| Free tier | Yes (1,500 req/day) | Yes (with billing account) |
| Production ready | Yes | Yes (enterprise IAM, audit logging) |

For this teaching project we use **Google AI Studio** — one API key, works everywhere,
no `gcloud` CLI needed. In a large enterprise you'd switch to Vertex AI for IAM controls
and audit logging, but the Spring AI code is identical either way.

### Getting your API key (important — read carefully)

> **Note (June 2026):** Google changed their API key format. New keys start with `AQ.Ab...`
> (called Authorization keys). The old `AIza...` format (Standard keys) is being phased out
> by September 2026. Both formats work the same way in API calls — just use whatever
> AI Studio gives you.

1. Go to [Google AI Studio](https://aistudio.google.com/app/apikey)
2. Sign in with a **personal Google account** (not a GCP workspace account if you can avoid it)
3. Click **"Create API key"** → **"Create API key in new project"**
    - ⚠️ Choose **"new project"** — do NOT pick an existing GCP project that has billing enabled.
      If you use a billing-enabled project, the free tier quota is set to `0` and every call
      returns `429 RESOURCE_EXHAUSTED`.
4. Copy the key and store it somewhere safe — you won't see it in full again

### Free tier limits (as of June 2026)

- **Gemini 2.5 Flash**: 1,500 requests/day, 1 million tokens/minute — plenty for development

### Common quota pitfall — `limit: 0`

If you see this error:
```
429: Quota exceeded for metric: generate_content_free_tier_requests, limit: 0
```

It means your API key is tied to a **GCP project with billing enabled**. The fix:
1. Go to [AI Studio](https://aistudio.google.com) → Dashboard → Projects
2. Create a **brand new project** (not imported from GCP Console)
3. Create a new API key in that project
4. Update `GEMINI_API_KEY` in your IntelliJ run config

### Model names change — always check

Google deprecates Gemini models over time. If you see:
```
404: This model models/gemini-X.Y-flash is no longer available
```

Go to [AI Studio Models](https://aistudio.google.com) or the
[Gemini API docs](https://ai.google.dev/gemini-api/docs/models) to find the current model name
and update `spring.ai.openai.chat.options.model` in `application.properties`.

As of June 2026: use `gemini-2.5-flash`.

### Setting the API key — never hardcode it

```properties
# Wrong — key committed to git
spring.ai.openai.api-key=AQ.Ab8RN6JxMJ...

# Right — read from environment variable
spring.ai.openai.api-key=${GEMINI_API_KEY:your-gemini-api-key}
```

The `${GEMINI_API_KEY:fallback}` syntax: read from the `GEMINI_API_KEY` env var; if not set,
use the fallback string (which will fail gracefully at request time, not at startup).

Set it in IntelliJ: **Run → Edit Configurations → Environment variables → `GEMINI_API_KEY=...`**

After updating the env var, always do a **full Stop → Start** in IntelliJ. Hot reload does not
re-read environment variables.

### Security: what NOT to do

- Never commit an API key to git (add `.env` to `.gitignore`)
- Never log it (`log.info("key={}", apiKey)`)
- Rotate the key immediately if it leaks — Google's auth keys have fast-acting leak detection

---

## Part 3: Spring AI Configuration

### Spring Boot 4.x compatibility — use Spring AI 2.0.0

> **Critical:** Spring AI 1.0.0 is NOT compatible with Spring Boot 4.x (Spring Framework 7.x).
> Spring Boot 4.x introduced breaking changes to `HttpHeaders` and removed
> `RestClientAutoConfiguration`. Spring AI 2.0.0 (released June 2026) restored compatibility.
> Always use Spring AI 2.0.0+ with Spring Boot 4.x.

| Spring Boot version | Spring AI version |
|---|---|
| 3.3.x / 3.4.x | Spring AI 1.0.x ✅ |
| 4.0.x+ | Spring AI 2.0.0+ ✅ |
| 4.0.x+ | Spring AI 1.0.x ❌ (startup crash) |

### The dependency

We use `spring-ai-starter-model-openai` pointed at Gemini's OpenAI-compatible endpoint.
Spring AI 2.0.0 has no dedicated Gemini API-key starter — but Gemini exposes an
OpenAI-compatible REST API, so the OpenAI starter works without any GCP setup.

```xml
<properties>
    <spring-ai.version>2.0.0</spring-ai.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

The BOM (Bill of Materials) pins every Spring AI module to a compatible version so you never
have to manage individual version numbers.

### Why use the OpenAI starter for Gemini?

Google's Gemini API is compatible with the OpenAI REST spec at this endpoint:
```
https://generativelanguage.googleapis.com/v1beta/openai
```

This means the same `spring-ai-starter-model-openai` that talks to OpenAI's GPT models can
talk to Gemini — just by pointing `base-url` at Google's endpoint. The `ChatClient` API in
your application code is completely identical. If you ever want to switch to actual OpenAI,
you change two properties and nothing else.

### ChatClient vs ChatModel

`ChatModel` is the low-level interface — it takes a `Prompt` and returns a `ChatResponse`.
`ChatClient` wraps `ChatModel` with a fluent builder API and adds useful features like
default system prompts, advisors (for RAG, memory, etc.), and method chaining.

**Always inject `ChatClient`, not `ChatModel`**, unless you need access to raw response metadata.

### ChatClient.Builder pattern

Spring Boot auto-configures a `ChatClient.Builder` bean when a provider starter is on the
classpath. You inject the builder, configure defaults, and call `.build()`:

```java
@Bean
public ChatClient chatClient(ChatClient.Builder builder) {
    return builder.build();
}
```

Options set in `application.properties` are automatically picked up by the auto-configured
builder — you don't need to set them in code. This is the recommended pattern: keep all
tuneable values (model name, temperature, token limit) in properties, not in Java code.

### Configuration reference

```properties
# Point the OpenAI starter at Gemini's OpenAI-compatible endpoint
spring.ai.openai.api-key=${GEMINI_API_KEY:your-gemini-api-key}
spring.ai.openai.base-url=https://generativelanguage.googleapis.com/v1beta/openai
spring.ai.openai.chat.options.model=gemini-2.5-flash
spring.ai.openai.chat.options.temperature=0.7
spring.ai.openai.chat.options.max-tokens=4096
```

**Temperature** controls creativity vs. determinism:
- `0.0` — deterministic, same output every time (good for classification, data extraction)
- `0.7` — balanced (good for blog writing — creative but coherent)
- `1.0+` — highly creative / unpredictable (good for brainstorming)

**max-tokens** caps the response length. 4096 is enough for a full blog post section.
For short tasks (tag suggestions, excerpts) you could use 256 to save quota.

---

## Part 4: Testing the Setup

### The test endpoint

`GET /api/v1/ai/test` (ADMIN only) sends a fixed prompt and returns the response:

```java
String reply = chatClient.prompt()
        .user("Reply with exactly one sentence: 'Spring AI with Gemini is working correctly.'")
        .call()
        .content();
```

Breaking this down:
- `.prompt()` — starts building a new request
- `.user("...")` — sets the user message (what you'd type in a chat)
- `.call()` — sends the request synchronously and waits for the full response
- `.content()` — extracts the text string from the `ChatResponse`

### Manual verification steps

1. Set `GEMINI_API_KEY` in IntelliJ run config
2. **Full Stop → Start** the app (env vars are not picked up by hot reload)
3. Login as ADMIN to get a JWT token:
   ```bash
   curl -X POST http://localhost:8082/api/v1/auth/login \
     -H "Content-Type: application/json" \
     -d '{"email":"admin@blog.com","password":"Password123!"}'
   ```
4. Call the test endpoint:
   ```bash
   curl -H "Authorization: Bearer <token>" http://localhost:8082/api/v1/ai/test
   ```
5. Expected response:
   ```json
   { "message": "Spring AI with Gemini is working correctly." }
   ```

### Handling common errors

| Error | Cause | Fix |
|---|---|---|
| `404: model no longer available` | Gemini deprecated the model | Update `spring.ai.openai.chat.options.model` to `gemini-2.5-flash` |
| `429: limit: 0` | API key tied to a billing-enabled GCP project | Create a new key in a fresh AI Studio project |
| `429: rate limit hit` | Actual free tier rate limit | Wait 1 minute and retry |
| `500` on endpoint call | App running stale build | Full Stop → Start in IntelliJ |
| `401 Unauthorized` from Gemini | Wrong or missing API key | Check `GEMINI_API_KEY` env var value |
| App won't start | Spring AI 1.0.0 on Spring Boot 4.x | Use Spring AI BOM `2.0.0` |

### Logging AI requests

Add to `application.properties` to see the full request/response in logs (dev only):
```properties
logging.level.org.springframework.ai=DEBUG
```

---

*Parts 5–7 below were added in Commit #55.*

---

## Part 5: Prompt Engineering Basics

### What is a system prompt vs a user prompt?

Every call to a chat model involves two distinct messages:

| Message type | Who sets it | What it does |
|---|---|---|
| **System prompt** | Your application code | Establishes the AI's persona, rules, and output format |
| **User prompt** | The human (or your code on their behalf) | The actual question or instruction for this specific request |

Think of it like hiring a contractor. The system prompt is the job description ("you are a technical writer, always use Markdown, never give investment advice"). The user prompt is the specific task ("write a section on Redis eviction policies"). The contractor reads the job description once, then handles each task within those rules.

In Spring AI:

```java
chatClient.prompt()
    .system("You are a technical writer. Use Markdown. Be precise.")   // persona + rules
    .user("Explain Redis eviction policies with code examples.")        // specific task
    .call()
    .content();
```

### Why system prompts matter for consistency

Without a system prompt, the model defaults to a generic assistant mode — it may write in plain text instead of Markdown, switch tone randomly, or hallucinate technical details. A good system prompt locks in:

- **Role** — who the AI is pretending to be
- **Format** — Markdown, plain text, JSON, numbered list
- **Tone** — formal/informal, beginner-friendly/expert-level
- **Constraints** — what to avoid, length limits, disclaimers

The same user prompt gives dramatically different output depending on the system prompt. This is why a blog about Redis written by a "technical writer" looks nothing like one written by a "casual blogger."

### Components of a good system prompt

A high-quality system prompt has four parts:

**1. Role definition** — "You are a culinary writer and food critic." This triggers the model's internal representation of what that role sounds like, which influences word choice, structure, and confidence level.

**2. Guidelines** — specific rules for this role. "Include ingredient lists. Mention dietary considerations." These override the model's defaults. Be explicit — the model will not infer rules you leave implicit.

**3. Output format specification** — "Format: Markdown with clear sections." Without this, the model decides the format itself, and it may change between calls. Pinning the format makes downstream parsing reliable.

**4. Tone and audience** — "Tone: Warm, appetizing. Audience: Home cooks." These two lines change vocabulary, sentence length, and assumed knowledge level. "Home cooks" gets simpler explanations than "Michelin-starred chefs."

### Temperature and creativity control

Temperature controls how creative (random) vs predictable the model's output is:

| Temperature | Behaviour | Good for |
|---|---|---|
| 0.0–0.2 | Nearly deterministic — same input → same output | Parsing, extraction, classification |
| 0.5–0.7 | Balanced — coherent but varied | Blog writing, summaries |
| 0.9–1.0 | Highly creative — may be unpredictable | Brainstorming, poetry |

In `application.properties`:

```properties
spring.ai.openai.chat.options.temperature=0.7
```

For blog content generation, 0.7 is the sweet spot — creative enough to be interesting, stable enough to be publishable.

---

## Part 6: Category-based Prompts

### Why different categories need different prompts

A blog about Redis caching and a blog about sourdough bread require completely different writing styles. A technology post benefits from code blocks and technical precision. A food post benefits from sensory language and a warm tone. Using a single generic system prompt for both would produce mediocre output for both.

Category-based prompts solve this by selecting the appropriate persona before every AI call. The user does not need to think about this — they just pick a category, and the system prompt adapts automatically.

### How it works in this project

The `CategoryPromptTemplate` enum maps each category name to a system prompt:

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

    public static CategoryPromptTemplate fromCategoryName(String categoryName) {
        // Case-insensitive match, falls back to DEFAULT
        String upper = categoryName.trim().toUpperCase();
        for (CategoryPromptTemplate t : values()) {
            if (t.name().equals(upper)) return t;
        }
        return DEFAULT;
    }
}
```

The `PromptTemplateService` then composes the full system prompt by combining the category base prompt with a task-specific instruction block:

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

    private String basePrompt(Category category) {
        return CategoryPromptTemplate.fromCategoryName(category.getName()).getSystemPrompt();
    }
}
```

This separation of concerns is deliberate:
- `CategoryPromptTemplate` owns the *who the AI is*
- `PromptTemplateService` owns the *what the AI should do right now*

Each AI feature (outline, full post, expand, excerpt, tags) gets its own method in `PromptTemplateService`, all sharing the same category base.

### Mapping categories to writing styles

The categories in this project follow a simple naming convention — the `Category.name` field in the database is matched case-insensitively against the enum names. If a category named "Technology" exists in the DB, `CategoryPromptTemplate.fromCategoryName("Technology")` returns `TECHNOLOGY`.

Categories that don't match any enum value fall back to `DEFAULT`. This means you can add new categories to the database without touching the enum — they just use the generic professional writer persona until you add a dedicated entry.

### Extending for new categories

To add a new category (e.g., HEALTH):

1. Add an entry to `CategoryPromptTemplate`:

```java
HEALTH("""
        You are a health and wellness journalist.
        Guidelines:
        - Cite reputable sources when making health claims
        - Avoid diagnosing or prescribing
        - Use plain language accessible to non-medical readers
        ...
        """),
```

2. Add the category to the database via a Flyway migration (if it is a new seeded category). No other code changes needed — `PromptTemplateService` picks it up automatically via `fromCategoryName`.

### Testing prompts for quality

The fastest way to test a prompt is to call the `/api/v1/ai/test` endpoint while temporarily changing the system prompt. For more systematic testing:

1. Write the prompt in AI Studio's prompt playground first — iterate there where feedback is instant
2. Port the refined prompt into the enum
3. Call the real endpoint with a known topic and inspect the output
4. Adjust temperature if the output is too repetitive (raise) or too chaotic (lower)

---

## Part 7: Prompt Best Practices

### 1. Be specific and clear

Vague prompts produce vague output. Compare:

```
# Vague
"Write about Redis."

# Specific
"Write a blog post about Redis eviction policies for developers who already understand caching basics. 
 Include a code example showing LRU configuration in Spring Boot."
```

The second prompt constrains audience (developers with caching basics), topic (eviction policies), and required content (LRU + Spring Boot code). Every constraint narrows the output toward what you actually want.

### 2. Specify output format explicitly

If you need to parse the AI's response programmatically, the format specification is critical:

```
# For outline parsing (numbered list)
"Return a numbered list of section headings. Example: 1. Introduction  2. Core Concepts"

# For tag parsing (comma-separated)
"Return tags as a comma-separated list only. No extra text. Example: redis, caching, spring-boot"
```

Without explicit format instructions, the model may add preamble ("Sure! Here are some tags:"), bullet points, or markdown that breaks your parser.

### 3. Provide examples when needed

Few-shot examples are powerful when format matters:

```
"Suggest tags. Return only a comma-separated list.
 Example input: A post about Redis caching in Spring Boot
 Example output: redis, caching, spring-boot, java, performance"
```

The model learns the pattern from the example and applies it consistently. This is especially useful for tag normalization (lowercase, no spaces) where the model's default may vary.

### 4. Set constraints and limitations

Constraints prevent the model from going out of bounds:

```
"Maximum 2-3 sentences. Do not include markdown. No headers."  ← for excerpts
"Total length: 800-1500 words."                                ← for full posts
"Avoid specific investment advice."                            ← for finance posts
```

Without length constraints, the model will write as much as it feels like. Without content constraints, it may cross into territory you don't want (legal disclaimers, medical advice, etc.).

### 5. Iterate and improve based on results

Prompts are not write-once. The first version almost always has gaps. The improvement cycle:

1. Generate 5–10 samples with your current prompt
2. Identify the most common failure modes ("the outline always has 3 sections instead of 5–8")
3. Add a constraint that addresses the failure ("Include exactly 5–8 sections. If you include fewer, the output is considered incomplete.")
4. Re-generate and compare

The prompts in `CategoryPromptTemplate` were iterated several times. Treat them as living configuration, not finished code.

---

*Next: Part 8 — Building the AI Service (added in Commit #56)*
