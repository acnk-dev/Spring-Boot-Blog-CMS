# Diagram 3 — `generatePost` Internal AI Call Chain

`POST /api/v1/ai/post` looks like one AI call from the outside, but `AIServiceImpl.generatePost()` internally chains **three sequential Gemini calls** before returning. This diagram makes that hidden fan-out visible.

```mermaid
flowchart TD
    A([Client calls POST /api/v1/ai/post]) --> B[AIController\nusage check + increment]
    B --> C[AIServiceImpl.generatePost]

    C --> D["① Gemini Call — Write Post\nChatClient with PostSystemPrompt + outline\n→ full Markdown content string"]
    D --> E[extractTitle\nfind first '# ' heading → title string]

    E --> F["② Gemini Call — Generate Excerpt\ngenerateExcerpt internally\nChatClient with ExcerptSystemPrompt\n→ 2-3 sentence summary"]

    F --> G["③ Gemini Call — Suggest Tags\nsuggestTags internally\nChatClient with TagSuggestionSystemPrompt\n→ comma-separated list → List&lt;String&gt;"]

    G --> H["Build GeneratePostResponse\ntitle + content + excerpt + suggestedTags + generatedAt"]
    H --> I([Return to client])

    style D fill:#dbeafe,stroke:#3b82f6
    style F fill:#dbeafe,stroke:#3b82f6
    style G fill:#dbeafe,stroke:#3b82f6
```

## The three Gemini calls in code

```
generatePost()
│
├─ chatClient.prompt()...call()          ← Call ① — write the post body
│
├─ generateExcerpt(excerptRequest)
│   └─ chatClient.prompt()...call()     ← Call ② — summarise into excerpt
│
└─ suggestTags(tagRequest)
    └─ chatClient.prompt()...call()     ← Call ③ — extract tag list
```

`generateExcerpt` and `suggestTags` are public methods on `AIService`. `generatePost` reuses them internally — the same logic that handles the standalone `/excerpt` and `/suggest-tags` endpoints. This is **method reuse without code duplication**.

## Usage quota implication

The controller increments the usage counter **once** before calling `generatePost`. Even though three Gemini calls happen internally, only one usage credit is deducted. This is a deliberate product decision: the user requested one generation, the internal fan-out is an implementation detail.

Compare this to image generation (Diagram 4), where the controller explicitly increments **twice** because the two-agent structure is intentionally exposed as a cost signal.
