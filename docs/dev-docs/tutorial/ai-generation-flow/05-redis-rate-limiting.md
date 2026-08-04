# Diagram 5 — Redis-Backed Monthly Rate Limiting

Every AI endpoint calls `AIUsageService.checkAndIncrementUsage()` before doing any AI work. This diagram walks through the full decision tree, including the Redis key structure and how the TTL auto-resets the counter at month end.

```mermaid
flowchart TD
    A([checkAndIncrementUsage\nuserID, role]) --> B{role == ADMIN?}
    B -- Yes --> C([Return immediately\nno limit for admins])
    B -- No --> D["Build Redis key\nai:usage:{userId}:{yyyy-MM}\ne.g. ai:usage:42:2026-06"]

    D --> E["GET key from Redis\n→ raw string or null"]
    E --> F["Parse to int\nnull → 0"]

    F --> G{current >= limit?}

    G -- Yes --> H([Throw AIUsageLimitException\n429 Too Many Requests])

    G -- No --> I["INCR key\n→ atomic increment\nnewCount = current + 1"]

    I --> J{newCount == 1?\nfirst write this month}
    J -- Yes --> K["EXPIRE key\nTTL = seconds until\nend of current month\n23:59:59 on last day"]
    J -- No --> L([Return — request proceeds])
    K --> L

    style H fill:#fee2e2,stroke:#ef4444
    style C fill:#dcfce7,stroke:#22c55e
    style L fill:#dcfce7,stroke:#22c55e
```

## Role-based limits

| Role | Monthly limit |
|---|---|
| `SUBSCRIBER` | 5 |
| `AUTHOR` | 50 |
| `ADMIN` | Unlimited (bypass) |

These are defined as a static `Map<Role, Integer>` constant in `AIUsageServiceImpl` — no database lookup needed.

## The Redis key design

```
ai:usage:{userId}:{yyyy-MM}
         ↑         ↑
         user ID   current year-month (YearMonth.now())

Example: ai:usage:42:2026-06
```

The month is baked into the key name, so a new month automatically gets a **new key starting at 0** — no cron job or manual reset needed. The TTL set on first write (`EXPIRE`) ensures the old key is cleaned up from Redis memory once the month ends.

## Why `INCR` instead of GET + SET?

Redis `INCR` is **atomic** — it reads and increments in a single operation. If two requests arrive simultaneously for the same user, there is no race condition where both read `4`, both compare `4 < 5`, both pass the check, and both write `5`. One of them will see `5` returned from `INCR` and the limit is honoured correctly.

## The `GET` before `INCR` — is it a race?

Yes, technically there is a small window between `GET` (check) and `INCR` (increment) where a concurrent request could slip through one extra call past the limit. For AI generation quotas this is acceptable — the consequence of a single over-limit call is minor. A stricter alternative is a Lua script that combines check + increment atomically, but that added complexity is not warranted here.

## Usage response (`GET /api/v1/ai/usage`)

```mermaid
flowchart LR
    A([GET /usage]) --> B["GET ai:usage:{userId}:{yyyy-MM}\nfrom Redis"]
    B --> C{key exists?}
    C -- No --> D[used = 0]
    C -- Yes --> E["used = parseInt(value)"]
    D & E --> F["remaining = max(0, limit - used)\nresetsAt = last second of current month"]
    F --> G([Return AIUsageResponse])
```
