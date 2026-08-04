# Commit 47 — Spring AI 2.0.0 + Google Gemini Setup

## Overview

This commit integrates Spring AI into the blog-cms project, enabling AI-powered content
generation features in the commits that follow. We add the Spring AI dependency, configure
a `ChatClient` bean pointed at Google's Gemini model, and expose a smoke-test endpoint
(`GET /api/v1/ai/test`) that verifies the connection is healthy before building real features
on top of it.

The integration uses Google's **OpenAI-compatible Gemini endpoint** with a plain API key —
no Google Cloud project setup, no `gcloud` CLI, no Application Default Credentials required.

---

## Problem / Motivation

The blog CMS needs AI capabilities for Week 7 features: outline generation, full post writing,
content expansion, excerpt generation, tag suggestion, and image generation. All of these
require a configured AI client that:

1. Is vendor-neutral — so service code doesn't change if we swap from Gemini to OpenAI
2. Works with Spring Boot 4.x — which ruled out Spring AI 1.0.0
3. Requires only an API key — no GCP infrastructure for a teaching project
4. Can be verified in isolation before building features on top of it

---

## Decision

### Spring AI version — 2.0.0, not 1.0.0

Spring AI 1.0.0 is incompatible with Spring Boot 4.x. Two breaking changes in Spring
Framework 7.x (which Boot 4.x is built on) cause startup crashes:

1. `HttpHeaders.addAll(MultiValueMap)` — method signature changed in Spring Framework 7.x.
   Spring AI's HTTP client code calls the old signature and throws `NoSuchMethodError`.
2. `RestClientAutoConfiguration` — class was removed/renamed in Spring Boot 4.x.
   Spring AI's `@ConditionalOnBean` references the old class name and crashes at context load.

Spring AI 2.0.0 (released June 2026) explicitly restored Spring Framework 7.x compatibility.
We use `2.0.0`.

**Compatibility matrix:**

| Spring Boot | Spring AI |
|---|---|
| 3.3.x / 3.4.x | 1.0.x |
| 4.0.x+ | 2.0.0+ |

### Gemini via OpenAI-compatible endpoint, not a dedicated Gemini starter

Spring AI 2.0.0's BOM does not include a standalone Google AI Studio (API-key-based) starter.
The two official Gemini options are:

1. `spring-ai-starter-model-vertex-ai-gemini` — requires a GCP project, `gcloud` CLI, and
   Application Default Credentials. Too much infrastructure for a teaching project.
2. Call Gemini via the **OpenAI-compatible endpoint** using `spring-ai-starter-model-openai`
   — just an API key and a `base-url` override.

We chose option 2. Google's Gemini API is fully compatible with the OpenAI REST spec at:
```
https://generativelanguage.googleapis.com/v1beta/openai
```

The `ChatClient` API in service code is identical regardless of which path you use — so
switching to actual OpenAI (or Vertex AI) later requires changing two properties, not code.

### API key format — `AQ.Ab...` auth keys

As of June 2026, Google AI Studio generates **Authorization keys** (format: `AQ.Ab...`)
instead of Standard keys (format: `AIza...`). Authorization keys are bound to a Google Cloud
service account and have fast-acting leak detection. Standard keys are being phased out
by September 2026.

Both formats work identically in API calls — pass them as `Authorization: Bearer <key>`.

### Free tier — must use a fresh AI Studio project

API keys tied to GCP projects with billing enabled have `limit: 0` for the free tier quota.
This causes every call to return `429 RESOURCE_EXHAUSTED` immediately.

The fix: always create the API key in a **new project** in AI Studio, not an imported GCP
project. A fresh AI Studio project gets 1,500 free requests/day for Gemini 2.5 Flash.

---

## Changes Made

### `pom.xml`

Added Spring AI BOM in `<dependencyManagement>` and the OpenAI starter in `<dependencies>`:

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

The BOM pins all Spring AI module versions so you never manage them individually.

### `application.properties`

```properties
spring.ai.openai.api-key=${GEMINI_API_KEY:your-gemini-api-key}
spring.ai.openai.base-url=https://generativelanguage.googleapis.com/v1beta/openai
spring.ai.openai.chat.options.model=gemini-2.5-flash
spring.ai.openai.chat.options.temperature=0.7
spring.ai.openai.chat.options.max-tokens=4096
```

- `base-url` redirects the OpenAI starter to Gemini's endpoint
- `GEMINI_API_KEY` is read from an environment variable — never hardcoded
- All tuneable options live in properties so they can be overridden per environment

### `src/main/java/com/studylink/blogcms/config/AIConfig.java` (new)

```java
@Configuration
public class AIConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
```

Spring Boot auto-configures `ChatClient.Builder` from the properties above. We call `.build()`
to produce a singleton `ChatClient` bean that all AI services inject. The configuration
(model, temperature, tokens) is read from properties — no hardcoded values in Java.

### `src/main/java/com/studylink/blogcms/controller/AIController.java` (new)

```java
@GetMapping("/test")
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<Map<String, String>> testConnection() {
    String reply = chatClient.prompt()
            .user("Reply with exactly one sentence: 'Spring AI with Gemini is working correctly.'")
            .call()
            .content();
    return ResponseEntity.ok(Map.of("message", reply));
}
```

- Restricted to `ADMIN` — never exposed to end users
- Uses the `ChatClient` fluent API: `.prompt()` → `.user()` → `.call()` → `.content()`
- Returns the model's response directly so you can verify the connection is healthy

### `docs/tutorials/spring-ai-tutorial.md` (new)

Full tutorial covering Parts 1–4:
- Introduction to Spring AI and its abstractions
- Google Gemini setup — API key types, free tier, quota pitfalls
- Spring AI configuration — version compatibility, BOM, OpenAI-compatible endpoint
- Testing the setup — verification steps and a complete error troubleshooting table

---

## Concepts Introduced

### Spring AI BOM (Bill of Materials)

Think of ordering a burger combo at McDonald's — you don't individually pick the fries size
and drink size, the combo decides that for you. A BOM does the same for Maven dependencies.

Spring AI has 50+ modules: `spring-ai-openai`, `spring-ai-redis-store`, `spring-ai-advisors`,
`spring-ai-vertex-ai-gemini`, and many more. If you pick their versions yourself, you might
accidentally pick two that conflict. The BOM is a pre-tested list that says:
*"these exact versions all work together."*

```xml
<!-- Step 1: Import the BOM in dependencyManagement -->
<dependencyManagement>
    <dependency>
        <artifactId>spring-ai-bom</artifactId>
        <version>2.0.0</version>
        <type>pom</type>   <!-- tells Maven this is a BOM, not a regular jar -->
        <scope>import</scope>
    </dependency>
</dependencyManagement>

<!-- Step 2: Declare the dependency — no <version> needed, BOM provides it -->
<dependency>
    <artifactId>spring-ai-starter-model-openai</artifactId>
    <!-- version omitted intentionally — BOM manages it -->
</dependency>
```

Spring Boot itself uses the same pattern — that is why you never write `<version>` for
`spring-boot-starter-web`, `spring-boot-starter-data-jpa`, etc. The `spring-boot-starter-parent`
is a BOM.

---

### Why we chose the OpenAI starter for Gemini — and not a dedicated Gemini starter

There are three reasons this was the right call:

**Reason 1: There is no standalone Gemini API-key starter in Spring AI 2.0.0**

The only official Gemini option in the BOM is `spring-ai-starter-model-vertex-ai-gemini`.
Vertex AI requires:
- A GCP project with Vertex AI API enabled
- The `gcloud` CLI installed
- `gcloud auth application-default login` run once (saves credentials to disk)
- `GOOGLE_CLOUD_PROJECT_ID` env var

That is 30+ minutes of infrastructure setup just to say hello to a model. Not acceptable for
a teaching project where a student should be able to run the app in 5 minutes.

**Reason 2: Google deliberately made Gemini OpenAI-compatible**

Google built Gemini's REST API to match the OpenAI spec exactly — same endpoints, same request
and response JSON structure. This was a strategic decision: millions of developers already know
OpenAI's API, so making Gemini compatible means zero migration cost for switching.

```
# OpenAI endpoint
https://api.openai.com/v1/chat/completions

# Gemini endpoint — same spec, different URL
https://generativelanguage.googleapis.com/v1beta/openai/chat/completions
```

Spring AI's OpenAI starter just POSTs to whatever `base-url` you configure. Point it at
Google's URL and it works with Gemini transparently.

**Reason 3: Provider lock-in becomes a properties change, not a code change**

Because `ChatClient` abstracts everything, your service code has zero knowledge of the
underlying provider. Switching from Gemini to OpenAI GPT-4o is literally two property changes:

```properties
# Today — Gemini
spring.ai.openai.base-url=https://generativelanguage.googleapis.com/v1beta/openai
spring.ai.openai.chat.options.model=gemini-2.5-flash

# Tomorrow — switch to OpenAI (zero Java code changes)
spring.ai.openai.base-url=https://api.openai.com
spring.ai.openai.chat.options.model=gpt-4o
```

This is the core value proposition of Spring AI. Vendor lock-in is eliminated at the
infrastructure level, not by writing abstractions yourself.

---

### Why do separate provider starters exist at all if one works for all?

This is a great question. If Gemini is OpenAI-compatible, why does
`spring-ai-starter-model-vertex-ai-gemini` exist?

The answer: **not all providers are OpenAI-compatible, and OpenAI-compatible providers
still have provider-specific capabilities that the OpenAI spec does not expose.**

Here is the breakdown:

| Scenario | Use OpenAI starter | Use dedicated starter |
|---|---|---|
| Provider is OpenAI-compatible (Gemini, Groq, Together AI) | ✅ Works fine | Not needed |
| Provider has a proprietary API (Anthropic, Cohere, Ollama) | ❌ Does not work | ✅ Required |
| Need provider-specific features (Gemini grounding, Anthropic vision params) | ❌ Not exposed | ✅ Required |
| Simple chat/completion use case | ✅ Simpler setup | Works but overkill |

**Anthropic Claude**, for example, uses a completely different API — it has no OpenAI
compatibility layer. You must use `spring-ai-starter-model-anthropic`. Same for **Ollama**
(local models), **Amazon Bedrock**, and others.

**Vertex AI Gemini** has its own starter because Vertex AI supports features that the
OpenAI-compatible endpoint does not expose: grounding with Google Search, safety filters with
fine-grained thresholds, audit logging to Cloud Logging, and multimodal inputs with
Gemini-specific parameters. If you need those features in production, you use the Vertex AI
starter. For standard chat/generation — which is everything we do in this project — the
OpenAI-compatible endpoint is sufficient and far simpler to set up.

**Rule of thumb:**
- Dev / teaching / standard chat → OpenAI starter pointed at any compatible provider
- Production with provider-specific features → dedicated starter for that provider

---

### ChatClient fluent API

The main entry point for all AI interactions in Spring AI 2.0.0:

```java
String text = chatClient.prompt()      // start a request
    .system("You are a blog writer")   // set the system prompt (optional)
    .user("Write about Redis caching") // set the user message
    .call()                            // send synchronously
    .content();                        // extract text from response
```

`.call()` is synchronous — the thread blocks until Gemini responds. For streaming responses
(showing output word by word), use `.stream()` instead. We use `.call()` throughout this
project for simplicity.

### OpenAI-compatible endpoints

Many AI providers (Google Gemini, Azure OpenAI, Groq, Together AI) implement the OpenAI
REST API spec. This means any client built for OpenAI — including Spring AI's OpenAI starter
— works with these providers by just changing `base-url`. It's a de facto standard for
LLM APIs.

### Authorization keys (Google, June 2026)

Google replaced Standard API keys (`AIza...`) with Authorization keys (`AQ.Ab...`) as the
default in AI Studio. Auth keys are bound to a service account, support granular IAM
permissions, and have automated leak detection. Standard keys will be rejected by September 2026.
The key format changed but usage is identical — pass as `Authorization: Bearer <key>`.

---

## Testing Checklist

- [ ] App starts without errors after adding Spring AI 2.0.0 dependency
- [ ] `GEMINI_API_KEY` is set in IntelliJ run configuration (full Stop → Start required)
- [ ] `POST /api/v1/auth/login` with `admin@blog.com` / `Password123!` returns a JWT
- [ ] `GET /api/v1/ai/test` with the JWT returns `{ "message": "Spring AI with Gemini is working correctly." }`
- [ ] `GET /api/v1/ai/test` without a JWT returns `401`
- [ ] `GET /api/v1/ai/test` with a non-ADMIN JWT returns `403`
- [ ] Changing `GEMINI_API_KEY` to an invalid value causes a `401` from Gemini (not a startup crash)
