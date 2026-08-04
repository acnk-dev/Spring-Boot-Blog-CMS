# Diagram 4 — Two-Agent Image Generation Pipeline

`POST /api/v1/ai/generate-image` uses a **two-agent pattern**: one agent crafts the image description (text), a second agent turns that description into pixels. This is why it costs 2 usage credits.

```mermaid
sequenceDiagram
    actor Client
    participant Controller as AIController
    participant UsageSvc as AIUsageService
    participant Redis
    participant ImgSvc as ImageGenerationServiceImpl
    participant DB as PostgreSQL
    participant Agent1 as ImagePromptAgent\n(Agent 1)
    participant ChatClient as ChatClient (Spring AI)
    participant GeminiChat as Gemini Flash API\n(text)
    participant GeminiImg as Gemini 2.5 Flash Image API\n(image)
    participant Disk as Local Disk\n/uploads/ai-images/

    Client->>Controller: POST /api/v1/ai/generate-image\n{title, categoryId, style}

    Note over Controller: Deduct 2 credits (2 AI agents)
    Controller->>UsageSvc: checkAndIncrementUsage (credit 1)
    UsageSvc->>Redis: INCR key
    Controller->>UsageSvc: checkAndIncrementUsage (credit 2)
    UsageSvc->>Redis: INCR key

    Controller->>ImgSvc: generateFeaturedImage(request)

    ImgSvc->>DB: findById(categoryId) → Category
    DB-->>ImgSvc: category name

    ImgSvc->>Agent1: generateImagePrompt(title, categoryName, style)

    Note over Agent1: AGENT 1 — Prompt Crafting
    Agent1->>ChatClient: prompt().system(IMAGE_PROMPT_SYSTEM).user(...).call()
    ChatClient->>GeminiChat: POST generateContent (text model)
    GeminiChat-->>ChatClient: detailed visual description string
    ChatClient-->>Agent1: imagePrompt string
    Agent1-->>ImgSvc: imagePrompt string

    alt ai.image.enabled = false
        ImgSvc-->>Controller: GenerateImageResponse\n(placeholder URL, prompt shown)
    else ai.image.enabled = true
        Note over ImgSvc: AGENT 2 — Image Generation
        ImgSvc->>GeminiImg: POST generateContent\n{contents, generationConfig: {responseModalities: ["IMAGE"]}}\nx-goog-api-key header
        GeminiImg-->>ImgSvc: JSON {candidates: [{content: {parts: [{inlineData: {data: base64}}]}}]}

        ImgSvc->>ImgSvc: Base64.decode(data) → byte[]
        ImgSvc->>Disk: Files.write(ai-generated-{uuid}.png)
        Disk-->>ImgSvc: saved

        ImgSvc-->>Controller: GenerateImageResponse\n{imageUrl, generatedPrompt, generatedAt}
    end

    Controller-->>Client: 200 OK
```

## Why two separate agents?

| Agent | Tool | Input | Output |
|---|---|---|---|
| Agent 1 — `ImagePromptAgent` | Gemini Flash (text) | Blog title + category + style preference | Detailed visual description string |
| Agent 2 — `ImageGenerationServiceImpl` | Gemini 2.5 Flash Image (image REST) | Visual description string | PNG file bytes |

Separating the agents gives control over the prompt. The Gemini image model produces better results when given rich, specific visual descriptions — not raw blog titles. Agent 1 bridges that gap. This is the **prompt-amplification pattern**: use a cheap text model to turn sparse input into rich input for an expensive generative model.

## The `ai.image.enabled` feature flag

The flag lets the app run in a demo or test environment where you don't want to consume image API quota. Agent 1 always runs (so the generated prompt is visible in the response), but Agent 2 is skipped and a placeholder URL is returned instead.
