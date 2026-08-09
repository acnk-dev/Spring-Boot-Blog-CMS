# Diagram 2 — End-to-End Request Lifecycle

This sequence diagram follows a single AI request (e.g. `POST /api/v1/ai/outline`) from the HTTP call all the way back to the JSON response. It shows exactly where JWT validation, usage checking, prompt assembly, and Gemini calls happen in order.

```mermaid
sequenceDiagram
    actor Client
    participant JWTFilter as JWT Filter
    participant Controller as AIController
    participant UsageSvc as AIUsageService
    participant Redis
    participant AIService as AIServiceImpl
    participant PTS as PromptTemplateService
    participant ChatClient as ChatClient (Spring AI)
    participant Gemini as Gemini Flash API
    participant DB as PostgreSQL

    Client->>JWTFilter: POST /api/v1/ai/outline\n{topic, categoryId}
    JWTFilter->>JWTFilter: Validate JWT, set SecurityContext
    JWTFilter->>Controller: Authenticated request

    Note over Controller: @PreAuthorize("isAuthenticated()")
    Controller->>Controller: currentUser() → extract User from SecurityContext

    Controller->>UsageSvc: checkAndIncrementUsage(userId, role)
    UsageSvc->>Redis: GET ai:usage:{userId}:{yyyy-MM}
    Redis-->>UsageSvc: "3" (current count)

    alt Limit reached (current >= limit)
        UsageSvc-->>Controller: throw AIUsageLimitException
        Controller-->>Client: 429 Too Many Requests
    else Under limit
        UsageSvc->>Redis: INCR ai:usage:{userId}:{yyyy-MM}
        UsageSvc->>Redis: EXPIRE key (TTL = seconds until month end) [only on first write]
        Redis-->>UsageSvc: new count = 4
        UsageSvc-->>Controller: ok
    end

    Controller->>AIService: generateOutline(request)
    AIService->>DB: findById(categoryId)
    DB-->>AIService: Category entity

    AIService->>PTS: getOutlineSystemPrompt(category)
    PTS-->>AIService: system prompt string (category-aware tone + task instructions)

    AIService->>ChatClient: prompt().system(...).user(...).call().content()
    ChatClient->>Gemini: POST /v1beta/models/gemini-flash:generateContent
    Gemini-->>ChatClient: raw text response (numbered list)
    ChatClient-->>AIService: String content

    AIService->>AIService: parseNumberedList(content) → List<String>
    AIService-->>Controller: GenerateOutlineResponse

    Controller-->>Client: 200 OK\n{topic, category, outline: [...], generatedAt}
```

## Why the usage check happens in the controller, not the service

The `AIService` is a pure domain service — it knows nothing about who is calling it or whether they have quota remaining. Quota enforcement is an HTTP/security-layer concern that belongs alongside `@PreAuthorize`, not buried in business logic. This keeps `AIService` reusable and testable in isolation.
