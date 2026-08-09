# Diagram 1 — Full Architecture Overview

This is the birds-eye view of the entire AI layer. It shows every class involved, what external system each one talks to, and how they depend on each other.

```mermaid
graph TB
    subgraph Client["Client (Postman / Browser)"]
        HTTP["HTTP Request"]
    end

    subgraph Security["Spring Security"]
        JWT["JWT Filter"]
        PreAuth["@PreAuthorize"]
    end

    subgraph Controller["Controller Layer"]
        AIC["AIController\n/api/v1/ai/*"]
    end

    subgraph UsageGate["Usage Gate"]
        AUS["AIUsageService"]
        Redis[("Redis\nai:usage:{userId}:{yyyy-MM}")]
    end

    subgraph TextAI["Text AI (AIService)"]
        AISVC["AIServiceImpl"]
        PTS["PromptTemplateService\n(system prompts per category)"]
        CC["ChatClient\n(Spring AI abstraction)"]
    end

    subgraph ImageAI["Image AI (ImageGenerationService)"]
        IGSVC["ImageGenerationServiceImpl"]
        IPA["ImagePromptAgent\n(Agent 1)"]
        CC2["ChatClient\n(Spring AI abstraction)"]
    end

    subgraph External["External APIs"]
        GeminiChat["Gemini Flash\n(chat / text)"]
        GeminiImg["Gemini 2.5 Flash Image\n(image generation)"]
    end

    subgraph Storage["Persistence"]
        PG[("PostgreSQL\ncategories table")]
        Disk["Local Disk\n/uploads/ai-images/"]
    end

    HTTP --> JWT --> PreAuth --> AIC
    AIC --> AUS
    AUS <--> Redis
    AIC --> AISVC
    AIC --> IGSVC

    AISVC --> PTS
    AISVC --> CC
    CC --> GeminiChat

    IGSVC --> IPA
    IPA --> CC2
    CC2 --> GeminiChat
    IGSVC -->|"REST POST (base64 response)"| GeminiImg
    IGSVC --> Disk

    AISVC --> PG
    IGSVC --> PG
```

## Key Takeaways

| Concern | Where it lives |
|---|---|
| Auth & role enforcement | `@PreAuthorize` on each controller method |
| Monthly usage quota | `AIUsageService` → Redis (auto-TTL at month end) |
| Text generation (outline, post, excerpt, tags, expand) | `AIServiceImpl` via Spring AI `ChatClient` |
| Image prompt crafting | `ImagePromptAgent` (Agent 1) via `ChatClient` |
| Actual image generation | `ImageGenerationServiceImpl` (Agent 2) via REST |
| System prompts per blog category | `PromptTemplateService` |
| Category data | PostgreSQL `categories` table |
| Generated images | Local disk under `/uploads/ai-images/` |
