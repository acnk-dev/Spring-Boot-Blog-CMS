# Rate Limiting: Algorithms, Design, and Trade-offs

## What is Rate Limiting?

**Rate limiting** is a technique to control the number of requests a client can make to an API within a specified time period. It's a form of **throttling** - a protective mechanism that enforces a maximum quota per client.

Instead of allowing unlimited requests, a rate limiter answers these questions:
- "Can this request proceed?" - Yes, the quota still has remaining capacity
- "Should this request be blocked?" - Yes, the quota is exhausted and must wait for the refill period

**Example:** An API might allow 100 requests per hour per user. The 101st request is rejected with an HTTP 429 status.

---

## Why Rate Limiting Matters

### Problem: Abuse and Resource Exhaustion

Without rate limiting:

```mermaid
graph LR
    A["Attacker: 10,000 requests/sec"] --> B["API Server"]
    C["Legitimate User: 10 requests/sec"] --> B
    B --> D["Server CPU: 100%"]
    B --> E["Memory: Exhausted"]
    B --> F["Legitimate requests timeout/fail"]
```

One malicious or misbehaving client can starve resources for everyone else.

### Benefits

| Benefit | Impact |
|---------|--------|
| **Availability** | Protects service from being overwhelmed by a single client |
| **Fair Usage** | Ensures all users get a fair share of API capacity |
| **Cost Control** | Prevents unexpected infrastructure bills (DDoS, runaway clients) |
| **Security** | Mitigates brute-force attacks (password guessing, token enumeration) |
| **Compliance** | Helps enforce SLA commitments (e.g., "paid tier = 500 req/hr, free = 100 req/hr") |

---

## Rate Limiting Algorithms

### 1. Token Bucket Algorithm

The **token bucket** is the most popular algorithm (used by AWS API Gateway, Stripe, GitHub). It models a bucket that fills with tokens over time.

#### How It Works

1. A bucket has a fixed **capacity** (e.g., 100 tokens)
2. Tokens are **refilled** at a constant rate (e.g., 100 tokens per 1 hour)
3. Each request **consumes 1 token**
4. If the bucket has tokens → request succeeds, decrement tokens
5. If the bucket is empty → request is rejected until tokens refill

#### Visualized Over Time

```mermaid
graph TD
    A["Time: 0s<br/>Bucket: 100 tokens<br/>Requests: 0"] --> B["Request 1<br/>Consumes 1 token"]
    B --> C["Bucket: 99 tokens"]
    C --> D["Time: 1800s (30 min)<br/>Refill: +50 tokens<br/>Rate: 100 tokens/hour"]
    D --> E["Bucket: 149 tokens<br/>Capped at 100"]
    E --> F["Request 2<br/>Consumes 1 token"]
    F --> G["Bucket: 99 tokens"]
    G --> H["Time: 3600s (1 hour)<br/>All tokens refilled"]
    H --> I["Bucket: 100 tokens"]
```

#### Lifecycle Example

```mermaid
sequenceDiagram
    participant C as Client
    participant B as Token Bucket
    
    C->>B: Request 1
    B->>B: Consume 1 token<br/>(Tokens: 99)
    B->>C: 200 OK
    
    C->>B: Request 2
    B->>B: Consume 1 token<br/>(Tokens: 98)
    B->>C: 200 OK
    
    Note over B: 1800 seconds (30 mins) pass<br/>Refill: +50 tokens<br/>Refill rate: 100 tokens/hour
    B->>B: Tokens: 148 (capped at 100)
    
    C->>B: Request 3
    B->>B: Consume 1 token<br/>(Tokens: 99)
    B->>C: 200 OK
    
    C->>B: 99 rapid requests
    B->>B: Consume 99 tokens<br/>Tokens: 0
    B->>C: 200 OK (all 99 succeed)
    
    C->>B: 1 more request
    B->>B: Try consume 1<br/>Tokens: 0 (bucket empty)
    B->>C: 429 Too Many Requests
```

#### Key Properties

| Property | Value |
|----------|-------|
| **Burst capacity** | Yes - the algorithm allows bursts up to the bucket capacity |
| **Fairness** | Fair - every client has their own separate bucket |
| **Refill model** | Greedy (continuous) or fixed intervals |
| **Implementation complexity** | Medium - you need to track tokens and last refill time |
| **Distributed cost** | Moderate - requires storing one integer per client |

#### Advantages

- This algorithm allows controlled bursts of requests (for example, 100 tokens per hour but all at once if needed).
- The refill happens smoothly as tokens accumulate continuously over the time period.
- The mental model is simple and intuitive - think of a bucket with a faucet filling it.
- This is an industry standard algorithm proven in production at major companies.

#### Disadvantages

- Clients can abuse the burst by using all tokens immediately, which starves later requests.
- The algorithm has no awareness of downstream capacity and doesn't adapt if the backend is struggling.

---

### 2. Leaky Bucket Algorithm

The **leaky bucket** is another time-honored approach. Think of a bucket with a hole in the bottom.

#### How It Works

1. Requests are **enqueued** into a bucket with fixed capacity
2. Requests are **processed** (leak out) at a constant rate (e.g., 2 req/sec)
3. If the bucket is full → new requests are rejected
4. Otherwise, requests wait in line until the "leak" processes them

#### Visualized

```mermaid
graph TD
    A["Incoming Requests<br/>100 req/sec"] --> B["Bucket<br/>Capacity: 50"]
    B --> C["Queue<br/>FIFO Order"]
    C --> D["Leak Rate<br/>2 req/sec"]
    D --> E["Processed<br/>Requests"]
    B --> F["Rejection<br/>Bucket full?"]
```

#### Example Timeline

```mermaid
sequenceDiagram
    participant Req as Requests
    participant Leak as Leaky Bucket
    participant API as Backend API
    
    Req->>Leak: 10 requests arrive (t=0)
    Leak->>Leak: Queue: 10 (all accepted)
    
    Req->>Leak: 5 requests arrive (t=0.5s)
    Leak->>Leak: Queue: 15 (all accepted)
    
    Leak->>API: Process 1 req (leak rate = 2/sec)<br/>t=0.5s
    Leak->>Leak: Queue: 14
    
    Leak->>API: Process 1 req<br/>t=1s
    Leak->>Leak: Queue: 13
    
    Req->>Leak: 50 requests arrive (t=1s)
    Leak->>Leak: Queue is full (capacity: 50)<br/>Only 37 can fit (50-13=37)<br/>Queue: 50 (capacity reached)
    Leak->>Req: 13 requests REJECTED<br/>(bucket is full)
    
    Leak->>API: Continue processing at 2/sec<br/>Gradually drains queue
```

#### Key Properties

| Property | Value |
|----------|-------|
| **Burst capacity** | No - the output rate is constant regardless of queue size |
| **Fairness** | FIFO - requests are processed in arrival order |
| **Refill model** | Fixed output rate (leak rate) |
| **Implementation complexity** | High - you need a queue and background worker process |
| **Distributed cost** | High - the entire queue must be stored and synchronized |

#### Advantages

- This algorithm prevents bursts because the output is always smooth and predictable.
- The latency is predictable because the queueing discipline is known and consistent.
- The processing order is fair because FIFO prevents starvation of waiting requests.

#### Disadvantages

- This is complex to implement because it requires queue management and a background worker process.
- There is high state overhead because the queue grows as requests are rejected or queued.
- This approach is not ideal for HTTP APIs because clients may timeout while waiting in the queue.
- It is hard to distribute across multiple servers because queue state must be synchronized.

---

### 3. Sliding Window Counter Algorithm

The **sliding window** counts requests in a moving time window.

#### How It Works

1. Define a **time window** (e.g., 1 hour)
2. Count all requests **within the current window**
3. If count ≥ limit → reject; else → accept
4. As time progresses, old requests **slide out** of the window

#### Visualized

```mermaid
graph LR
    subgraph W1["Window at t=0:20 → covers [0:00, 0:20]"]
        R1["Req @ 0:05"]
        R2["Req @ 0:10"]
        R3["Req @ 0:15"]
        R4["Req @ 0:20"]
    end

    subgraph W2["Window at t=1:06 → covers [0:06, 1:06]"]
        R2b["Req @ 0:10 (still inside)"]
        R3b["Req @ 0:15 (still inside)"]
        R4b["Req @ 0:20 (still inside)"]
        R5["Req @ 1:06 (new)"]
    end

    W1 -->|"t=1:06 — Req @ 0:05 slides out<br/>Window shifts forward"| W2
```

#### Example Timeline

```mermaid
sequenceDiagram
    participant C as Client
    participant SW as Sliding Window
    
    Note over SW: Window: [t-3600, t] (trailing 1 hour)<br/>Limit: 10
    
    C->>SW: Request @ t=100s<br/>Window=[0,100] Count=1
    SW->>C: 200 OK
    
    C->>SW: Request @ t=200s<br/>Window=[0,200] Count=2
    SW->>C: 200 OK
    
    Note over SW: ... 8 more requests<br/>up to t=1000s, count=10
    
    C->>SW: Request @ t=1000s<br/>Window=[0,1000] Count=10
    SW->>C: 200 OK
    
    C->>SW: Request @ t=1001s<br/>Window=[0,1001] Count=10 (limit reached)
    SW->>C: 429 Too Many Requests
    
    Note over SW: t=3701s: window=[101,3701]<br/>Request at t=100 slides out<br/>Count drops from 10 to 9
    C->>SW: Request @ t=3701s<br/>Count=10 (9 old + 1 new)
    SW->>C: 200 OK
    
    Note over SW: Requests slide out one by one<br/>as each one ages past 3600s<br/>Count gradually falls to 0
```

#### Key Properties

| Property | Value |
|----------|-------|
| **Burst capacity** | Possible at window boundaries |
| **Fairness** | Fair within the window |
| **Refill model** | Hard reset when window expires |
| **Implementation complexity** | Low-Medium - you need to track timestamps of requests |
| **Distributed cost** | Low - you just need a list of request timestamps |

#### Advantages

- This algorithm is simple to understand because you simply count requests within a time window.
- Memory usage is low because you only store recent request timestamps, not all historical data.
- Enforcement is accurate because the hard limit applies within the exact time window.

#### Disadvantages

- There is a boundary issue called the cliff effect - the limit suddenly resets when the window expires.
- Bursts at boundaries can occur because two full limits are possible within twice the window time.
- There is timestamp overhead because you must store every single request timestamp.

---

### 4. Fixed Window Counter Algorithm

The **fixed window** is the simplest: divide time into buckets and count per bucket.

#### How It Works

1. Divide time into **fixed intervals** (e.g., 1-hour buckets)
2. Count requests in **current interval**
3. If count ≥ limit → reject; else → accept
4. When the interval boundary passes → **reset counter** to 0

#### Visualized

```mermaid
graph LR
    A["Hour 1<br/>0:00-0:59<br/>Count: 0/100"] --> B["Hour 2<br/>1:00-1:59<br/>Count: 0/100"]
    B --> C["Hour 3<br/>2:00-2:59<br/>Count: 0/100"]
    
    subgraph W1 ["Hour 1 Requests"]
        D["Req 1 @ 0:05<br/>Count: 1"]
        E["Req 2 @ 0:30<br/>Count: 2"]
        F["Req 3 @ 0:59<br/>Count: 3"]
    end
    
    subgraph W2 ["Hour 2 Requests"]
        G["Req 4 @ 1:00<br/>Count: 1"]
        H["Req 5 @ 1:30<br/>Count: 2"]
    end
    
    A --> W1
    B --> W2
```

#### Example Timeline

```mermaid
sequenceDiagram
    participant C as Client
    participant FW as Fixed Window
    
    Note over FW: Window: [0:00-1:00)<br/>Limit: 100<br/>Count: 0
    
    C->>FW: Request @ 0:10<br/>Count=1
    FW->>C: 200 OK
    
    C->>FW: Request @ 0:30<br/>Count=2
    FW->>C: 200 OK
    
    Note over FW: ... 98 more requests by 0:59
    
    C->>FW: Request @ 0:59<br/>Count=100
    FW->>C: 200 OK
    
    C->>FW: Request @ 0:59<br/>Count=100 (limit)
    FW->>C: 429 Too Many Requests
    
    Note over FW: Window boundary:<br/>1:00 arrives
    FW->>FW: Reset count=0<br/>Window: [1:00-1:59]
    
    C->>FW: Request @ 1:00<br/>Count=1
    FW->>C: 200 OK
```

#### Key Properties

| Property | Value |
|----------|-------|
| **Burst capacity** | High at boundaries (2x limit possible) |
| **Fairness** | Fair within interval |
| **Refill model** | Hard reset at interval boundary |
| **Implementation complexity** | Very Low - just one counter and a timestamp |
| **Distributed cost** | Minimal - you only need to store one counter value |

#### Advantages

- This algorithm is trivial to implement because you only need one counter and a reset time.
- The state overhead is minimal because you store a single integer per client.
- The performance is efficient with fast lookup and update operations.

#### Disadvantages

- The cliff effect occurs when there is an abrupt reset at the boundary.
- Double-spend at boundaries is a real problem because a user can consume twice the limit in a short time:
    - Hour 1: 100 requests by 0:59:59
    - Hour 2: 100 requests from 1:00:00
    - Total in 2 seconds: 200 requests!

---

## Algorithm Comparison

```mermaid
graph TD
    A["Choose a Rate Limiting Algorithm"] --> B{What's your priority?}
    
    B -->|Bursts OK<br/>Simple| C["Token Bucket"]
    B -->|Smooth output<br/>FIFO fairness| D["Leaky Bucket"]
    B -->|Accurate window<br/>Low memory| E["Sliding Window"]
    B -->|Minimal state<br/>Very simple| F["Fixed Window"]
    
    C --> C1["Stripe, AWS,<br/>GitHub use this"]
    D --> D1["Complex to<br/>distribute"]
    E --> E1["Accurate,<br/>moderate cost"]
    F --> F1["Cliff effect<br/>at boundaries"]
```

| Algorithm | Complexity | Accuracy | Burst | Distributed | Best For |
|-----------|-----------|----------|-------|-------------|----------|
| **Token Bucket** | Medium | High | Yes | Easy | General-purpose APIs |
| **Leaky Bucket** | High | Very High | No | Hard | Real-time systems, video streaming |
| **Sliding Window** | Medium | Very High | No | Medium | Payment APIs, analytics |
| **Fixed Window** | Low | Medium | High burst | Very Easy | Simple services, prototyping |

---

## Real-World Examples

### Example 1: GitHub API Rate Limits

GitHub uses **token bucket**:
- **Unauthenticated**: 60 requests/hour
- **Authenticated**: 5,000 requests/hour per user
- **Premium**: Unlimited

Each request consumes 1 token. The bucket refills continuously over the hour.

**Why token bucket?**
- This algorithm allows power users to make 5,000 requests at once if needed through burst capability.
- The smooth refill mechanism prevents cliff effects that would frustrate users.
- The approach is fair to all users because each user has their own separate bucket.

### Example 2: Payment Gateway (Stripe)

Stripe uses **sliding window**:
- Track requests in a **moving 1-second window**
- Limit: 100 requests/second per API key

**Why sliding window?**
- Payments require precision timing - there should be no sudden burst from a fixed window reset.
- This algorithm prevents double-charges and race conditions that could occur with other approaches.
- High accuracy is required for payment systems to maintain trust and compliance.

### Example 3: CDN Traffic Shaping (Leaky Bucket variant)

A CDN uses **leaky bucket**:
- Incoming requests are queued
- Egress bandwidth is constant (e.g., 1 Mbps per client)

**Why leaky bucket?**
- This algorithm prevents traffic spikes from overwhelming the origin server.
- The load is smooth and predictable, making it easier to plan infrastructure.
- It is easy to manage origin connection pools because the load is constant and controlled.

### Example 4: DDoS Mitigation (Fixed Window)

A basic DDoS protection service uses **fixed window**:
- Track requests per IP per minute
- Block if > 10,000 requests/minute

**Why fixed window?**
- This algorithm is very fast to implement and deploy in an emergency.
- It is lightweight with minimal state overhead on the system.
- Bursts at boundaries are acceptable because DDoS protection is already an imperfect defense.

---

## Distributed Rate Limiting

When you have **multiple servers**, a single in-memory bucket is not sufficient. Consider this scenario:

```mermaid
graph TB
    Client["Client<br/>IP: 1.2.3.4"] --> LB["Load Balancer"]
    
    LB --> S1["Server A<br/>In-memory bucket: 99 tokens"]
    LB --> S2["Server B<br/>In-memory bucket: 99 tokens"]
    
    S1 --> Result1["Request 1 accepted<br/>(Server A thinks 99 tokens left)"]
    S2 --> Result2["Request 2 accepted<br/>(Server B thinks 99 tokens left)"]
    
    Result1 --> Problem["Problem: 2 requests passed<br/>but each server only saw 1"]
    Result2 --> Problem
    
    style S1 fill:#fcc
    style S2 fill:#fcc
    style Problem fill:#f99
```

**Problem:** Client hits both servers' limits concurrently - total requests exceed 100!

### Solution: Centralized State Store

```mermaid
graph TB
    C["Client"]
    
    C --> LB["Load Balancer"]
    
    LB --> S1["Server A"]
    LB --> S2["Server B"]
    
    S1 --> |Check bucket| Redis["Redis<br/>rate_limit:client_id: 99"]
    S2 --> |Check bucket| Redis
    Redis --> S1
    Redis --> S2
    
    S1 --> |Consume 1| Redis
    S2 --> |Blocked: 0 tokens| Redis
    
    S1 --> C
    S2 --> |429| C
```

**How it works:**

1. Each server queries **Redis** (central bucket store)
2. Server performs **atomic compare-and-swap** (CAS):
    - Read current tokens
    - If > 0: decrement and confirm
    - If = 0: abort (already consumed)
3. Only one server's CAS succeeds per request
4. Token state is **consistent across all servers**

#### Trade-off: Latency

```mermaid
graph LR
    A["In-Memory<br/>Bucket"] -->|1μs| B["Accept/Reject"]
    C["Redis<br/>Bucket"] -->|5ms| D["Accept/Reject"]
    
    style A fill:#cfc
    style C fill:#fcc
```

- **In-memory**: less than 1 microsecond latency
- **Redis**: approximately 1 to 5 milliseconds latency (network round-trip)

For most APIs, 5ms latency is acceptable. However, high-frequency trading or gaming applications might need in-memory buckets to avoid this overhead.

---

## Trade-offs and Considerations

### 1. Burst vs. Smoothness

```mermaid
graph LR
    A["Token Bucket<br/>100 req/hour"] --> B["Burst: All 100<br/>in 1 minute"]
    C["Leaky Bucket<br/>100 req/hour"] --> D["Smooth: ~1.67<br/>requests/minute"]
```

You should choose based on your expected workload pattern:
- **Bursty traffic** (e.g., job queues): Token bucket is better suited for unpredictable bursts.
- **Smooth traffic** (e.g., sensor data): Leaky bucket is better for consistent, continuous streams.

### 2. Accuracy vs. Simplicity

```mermaid
graph TD
    A["Algorithm"] --> B{Accuracy required?}
    B -->|High| C["Sliding Window<br/>or Leaky Bucket"]
    B -->|Medium| D["Token Bucket"]
    B -->|Low| E["Fixed Window"]
```

- Payment systems use sliding window for high accuracy requirements.
- General APIs use token bucket for a balance of simplicity and fairness.
- DDoS protection uses fixed window for speed and minimal overhead.

### 3. Single Server vs. Distributed

| Scenario | Approach |
|----------|----------|
| Single server, < 1000 req/s | In-memory bucket |
| Multiple servers, < 10,000 req/s | Redis with TTL |
| Multiple regions | Geo-replicated Redis + eventual consistency |
| Ultra-low latency required | In-memory with sync to Redis |

### 4. Response Behavior

When rate limit is exceeded, APIs typically respond:

```json
HTTP 429 Too Many Requests

{
  "error": "Too Many Requests",
  "message": "Rate limit exceeded",
  "retry_after": 3600
}

Headers:
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1704067200
Retry-After: 3600
```

**Key headers:**
- `Retry-After` - this header tells the client how many seconds to wait before retrying the request.
- `X-RateLimit-Remaining` - this header shows how many tokens are left in the current window.
- `X-RateLimit-Reset` - this header contains the Unix timestamp when the next refill will occur.

---

## Summary

| Aspect | Token Bucket | Leaky Bucket | Sliding Window | Fixed Window |
|--------|------|------|------|------|
| **Complexity** | Medium | High | Medium | Low |
| **Burst Tolerance** | Yes | No | No | Yes (boundary) |
| **Accuracy** | High | Very High | Very High | Medium |
| **Distributed Cost** | Low | High | Medium | Very Low |
| **Common Use Case** | APIs | Real-time | Payments | DDoS |
| **Industry Adoption** | Very High | Low | High | Medium |

**Recommendation for most APIs:** Start with token bucket because it provides a good balance of simplicity, accuracy, and burst tolerance. Then evaluate sliding window if accuracy becomes critical, or fixed window if simplicity is the paramount concern.

---

## References

- Stripe Rate Limiting: https://stripe.com/en-in/blog/rate-limiters
- AWS API Gateway: https://docs.aws.amazon.com/apigateway/latest/developerguide/api-gateway-request-throttling.html
- Bucket4j Documentation: https://github.com/vladimir-bukhtoyarov/bucket4j
- Google Cloud: https://cloud.google.com/architecture/rate-limiting-strategies-techniques
