# Learning notes: designing the AI service's async boundary

Context: extracting AI features (`blogcms-ai-service`) out of the monolith per
`plans/r2-file-storage-integration.md` and the LangChain4j microservice plan. This doc
tracks four iterations of *how the monolith and the AI service talk to each other*,
what was wrong with each one, and why the final design looks the way it does.

**Stated goal, explicitly, so it doesn't get lost**: this was never about hitting a
textbook definition of "proper microservices." The goal is narrower and more concrete —
stop AI generation latency (Gemini calls, image generation) from occupying main BE
request threads. Everything below is in service of that one goal, not purity.

---

## Design 1 — synchronous call, just in a different process

```
FE → AIController (monolith) → AIServiceClient (RestClient, blocking)
   → AI service internal API → Gemini → response flows all the way back
```

Looked like a microservice (separate deployable, separate repo, its own port).
Behaved like a monolith. The only thing that changed from "call `AIServiceImpl`
in-process" was that the call now goes over HTTP instead of a method call — the
monolith's request thread still blocks for the *entire* duration of the Gemini call,
just with network serialization overhead added on top.

**What was wrong**: extraction is supposed to buy independent scaling/failure. This
design doesn't get that — a slow Gemini call still pins one monolith thread per
in-flight request, same as if the code had never moved. At real concurrency (e.g. 500
simultaneous outline requests), Tomcat's thread pool exhausts exactly like it would
have in the monolith. "Different service" wasn't the same thing as "decoupled."

---

## Design 2 — async AI service, BE polls it for the result

```
BE dispatches job → AI service returns job id → BE loops: sleep, GET /status, repeat
   → until AI service says COMPLETED
```

Fixed the "blocked on Gemini" problem in *one* place and reintroduced it in another.
The monolith no longer waits on Gemini directly — but if the polling loop lives on the
same request thread that's serving the original caller, that thread is now blocked in
a sleep/poll loop for the same total duration as before. No net improvement at scale;
500 concurrent jobs still means 500 threads tied up, just spinning in a loop instead
of blocked on I/O.

**Lesson**: "make it async" only helps if the *waiting* also moves off the request
thread. Async dispatch + synchronous polling-in-a-loop is not actually async from the
caller's perspective.

---

## Design 3 — Redis status, push instead of pull, FE polls a cheap endpoint

```
FE → BE: dispatch job, get job id back immediately (thread freed in ms)
BE writes ai-job:{id} → PENDING in Redis
BE → AI service: fire-and-forget dispatch (fast ack, work continues elsewhere)
AI service finishes → writes ai-job:{id} → COMPLETED (+ result) back
                       [either via a webhook call to BE, or direct Redis write —
                        both considered; direct write chosen for simplicity at this
                        scale, see tradeoff notes below]
FE polls BE's /status/{id} → BE does one Redis GET, returns whatever's there
```

This is the pattern actually borrowed from the crawler service built during the
BrowserStack internship: crawler worker (always-listening consumer) writes results to
Redis, a background transform step ("results rake") reshapes them off the request
path, and the FE-facing endpoint only ever does a cheap read. Applied here the same
way, minus the queue (traffic doesn't justify one yet — see "what we deferred" below).

**Why this actually solves the chokepoint**: the monolith's thread pool usage
per request drops from *seconds* (blocked on Gemini) to *milliseconds* (one Redis
read or write). At 500 concurrent pollers hitting every 2-3s, back-of-envelope thread
occupancy is roughly:

```
concurrent threads ≈ (requests/sec) × (time per request)
                    ≈ (500 / 2s) × 0.005s
                    ≈ 1.25 threads on average
```

versus ~500 threads pinned simultaneously in Design 1. This is the actual fix — not
"add async," specifically "stop blocking threads on slow I/O, whether that's a direct
call or a polling loop."

**Webhook vs direct Redis write** — both were considered as the "AI service reports
completion" mechanism:
- *Webhook* (AI service `POST`s result to a monolith callback endpoint): keeps the
  API-level boundary — AI service never touches BE infra directly, needs no BE
  credentials, only ever calls a URL handed to it per-request. Costs one more
  controller + retry/idempotency handling (at-least-once delivery, duplicate
  callbacks, stuck-PENDING timeout sweep).
- *Direct Redis write*: AI service writes straight into the monolith's Redis. Simpler,
  fewer moving parts, matches what the crawler already did. Judged acceptable here
  (unlike sharing Postgres) because Redis job-status is ephemeral coordination state
  (TTL'd, no schema/migrations, no long-lived business truth) rather than owned
  business data — a materially lighter form of coupling than a shared relational
  schema.

---

## Design 4 (current) — pseudo-shared DB for job results

Same async/Redis-status flow as Design 3, plus: an `ai_jobs` table living in the
monolith's own Postgres instance, but with **exclusive read/write ownership by the AI
service** and **read-only access for the monolith**. Redis still holds the `PENDING` /
`COMPLETED` flag (and TTLs out); the table holds the durable result payload instead of
keeping it only in Redis.

**Explicitly named as a known compromise, not textbook separation.** Table-level
ownership (one writer, everyone else read-only, no cross-boundary joins) removes the
*logical* coupling problem — no ambiguous writes, no schema free-for-all. It does
**not** remove the *physical* coupling problem:
- shared connection pool — AI service writes compete with monolith CRUD for the same
  instance's connection budget
- shared failure domain — a Postgres incident now takes down AI result persistence
  too, undoing "AI features degrade independently of the blog"
- migrations owned by a second, independently-deployed codebase against one instance
- AI service now holds real DB credentials, where before it held none

**Why this is fine for now**: neither the DB nor the queue is the current bottleneck.
The chokepoint that actually existed — main BE threads pinned on Gemini latency — is
fully solved by Design 3's async/Redis pattern. The DB sharing is a deliberate,
monitored trade-off taken *because* it isn't the bottleneck yet, not because it's
architecturally correct. This is evolutionary architecture / YAGNI: build the
simplest thing that fixes the real, current, measured problem; instrument; upgrade the
specific piece that actually redlines, when it redlines — not before.

---

## What we deliberately deferred, and the trigger to revisit

Two separate future upgrades, watched independently — hitting one doesn't imply the
other is needed:

| Upgrade | Trigger signal | What changes |
|---|---|---|
| Split `ai_jobs` into its own DB instance | Connection pool exhaustion, AI writes visibly slowing blog CRUD, or a Postgres incident on one side taking down the other | Pure infra move — same table shape, same ownership rules, no API/contract changes |
| Introduce a real queue (Redis Streams / Kafka) instead of fire-and-forget dispatch | Multiple AI service replicas needed, or requests arrive faster than Gemini can drain them and 503s become a real problem instead of a rare edge case | Changes the dispatch step only — everything downstream (status flag, webhook/write, polling) stays the same |

Both are cheap to defer because the boundary (job id, Redis status, single writer per
table) was already drawn correctly from the start — upgrading either piece later is an
infrastructure swap, not a redesign.
