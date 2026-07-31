# Commit 32 — Fix: Tighten SecurityConfig with Method-Specific Route Rules

## Overview

The Spring Security filter chain had two bugs: write endpoints on `/api/v1/posts/**` were accidentally open to unauthenticated callers, and all category/tag read endpoints incorrectly required a valid JWT. This commit replaces the two broad wildcard rules with explicit HTTP method-specific matchers that precisely reflect what each controller actually allows.

---

## Problem

The original `SecurityConfig` had this filter chain:

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/v1/auth/**").permitAll()
    .requestMatchers("/api/v1/posts/**").permitAll()  // ← bug 1
    .anyRequest().authenticated()                     // ← bug 2
)
```

### Bug 1 — Write endpoints on `/posts` were open to everyone

`.requestMatchers("/api/v1/posts/**").permitAll()` matches **all HTTP methods** — GET, POST, PUT, DELETE. That means the filter chain waved through unauthenticated requests to:

- `POST /api/v1/posts` (create post)
- `PUT /api/v1/posts/{id}` (update post)
- `DELETE /api/v1/posts/{id}` (delete post)
- `POST /api/v1/posts/{id}/publish`
- `POST /api/v1/posts/{id}/image`

These requests weren't blocked at the filter chain — they reached the controller and only then got rejected by `@PreAuthorize`. That left one layer of defence instead of two.

### Bug 2 — Read endpoints on `/categories` and `/tags` required auth

Everything not matched by the two explicit rules fell under `.anyRequest().authenticated()`. That silently made these endpoints require a JWT:

- `GET /api/v1/categories`, `GET /api/v1/categories/{id}`, `GET /api/v1/categories/slug/{slug}`
- `GET /api/v1/tags`, `GET /api/v1/tags/{id}`

All of these are intentionally public (the controllers have no `@PreAuthorize` on GET methods). Any frontend or anonymous user trying to browse categories or tags would get a 401, which is wrong.

---

## Full Endpoint Access Matrix

This is the complete access model derived from reading all four controllers:

### AuthController (`/api/v1/auth/**`)

| Endpoint | Auth required |
|---|---|
| `POST /register` | No |
| `POST /login` | No |
| `POST /refresh` | No |

### PostController (`/api/v1/posts/**`)

| Endpoint | Auth required | Role |
|---|---|---|
| `GET /posts` | No | — |
| `GET /posts/{id}` | No | — |
| `GET /posts/slug/{slug}` | No | — |
| `GET /posts/user/{userId}` | No | — |
| `GET /posts/search` | No | — |
| `GET /posts/category/{categoryId}` | No | — |
| `GET /posts/tag/{tagId}` | No | — |
| `POST /posts` | Yes | AUTHOR or ADMIN |
| `PUT /posts/{id}` | Yes | AUTHOR or ADMIN |
| `DELETE /posts/{id}` | Yes | AUTHOR or ADMIN |
| `POST /posts/{id}/publish` | Yes | AUTHOR or ADMIN |
| `POST /posts/{id}/image` | Yes | AUTHOR or ADMIN |

### CategoryController (`/api/v1/categories/**`)

| Endpoint | Auth required | Role |
|---|---|---|
| `GET /categories` | No | — |
| `GET /categories/{id}` | No | — |
| `GET /categories/slug/{slug}` | No | — |
| `POST /categories` | Yes | ADMIN |
| `PUT /categories/{id}` | Yes | ADMIN |
| `DELETE /categories/{id}` | Yes | ADMIN |

### TagController (`/api/v1/tags/**`)

| Endpoint | Auth required | Role |
|---|---|---|
| `GET /tags` | No | — |
| `GET /tags/{id}` | No | — |
| `POST /tags` | Yes | ADMIN |
| `DELETE /tags/{id}` | Yes | ADMIN |

---

## Decision — Two Layers of Security

### The two mechanisms

Spring Security provides two independent ways to enforce access control:

1. **Filter chain (`authorizeHttpRequests`)** — evaluates the HTTP request *before* it reaches any controller. If the request doesn't match, it is rejected with 401/403 immediately. No controller code runs.

2. **Method security (`@PreAuthorize`)** — evaluates after the request passes the filter chain, at the point where the controller method is about to execute. Can check roles, ownership, and any business condition expressible in Spring Expression Language (SpEL).

### Option A — Rely on `@PreAuthorize` only; keep filter chain open

Keep `permitAll()` broad and let `@PreAuthorize` do all the work.

*Rejected because:* Single layer of defence. If a `@PreAuthorize` annotation is accidentally omitted on a new endpoint, that endpoint is silently public. The filter chain is a safety net — it should fail-safe to authenticated.

### Option B — Encode full RBAC in the filter chain; remove `@PreAuthorize`

Replicate every role rule in `SecurityConfig` and delete all `@PreAuthorize` annotations.

*Rejected because:* The filter chain cannot check ownership — it doesn't know who owns a specific post. `@PreAuthorize` on `updatePost` doesn't just check the role; the service also verifies `userId == post.author.id || isAdmin`. That logic must stay in the service/controller layer.

### Option C — Method-specific filter chain rules + keep `@PreAuthorize` (chosen)

Use `HttpMethod`-qualified matchers in the filter chain to correctly open read routes and lock write routes. Keep `@PreAuthorize` for fine-grained role checks and ownership logic.

*Chosen because:*
- Filter chain enforces authentication before any code runs — defence in depth.
- `@PreAuthorize` handles the role granularity and ownership that the filter chain cannot express.
- The two layers are complementary, not redundant: the filter chain asks "is this user logged in and eligible?"; `@PreAuthorize` asks "does this user own this specific resource?"
- Any new endpoint that isn't explicitly listed falls under `.anyRequest().authenticated()` — fail-safe default.

---

## Concept: HTTP Method-Specific Matchers in Spring Security

### What is `HttpMethod` matching?

`requestMatchers(HttpMethod.GET, "/api/v1/posts/**")` creates a rule that only activates when the HTTP method is `GET` **and** the path matches the pattern. A `POST` to the same path does not match this rule and falls through to the next rule.

This is different from `requestMatchers("/api/v1/posts/**")`, which matches **any** method on that path.

### How Spring Security evaluates rules

Rules are evaluated **in order**, top to bottom. The first matching rule wins — subsequent rules are not checked. This is why order matters:

```java
.requestMatchers(HttpMethod.GET, "/api/v1/posts/**").permitAll()        // rule 1
.requestMatchers(HttpMethod.POST, "/api/v1/posts/**").hasAnyRole(...)   // rule 2
```

A `GET /api/v1/posts` request matches rule 1 and is permitted. It never reaches rule 2.
A `POST /api/v1/posts` request does not match rule 1 (wrong method), falls through to rule 2, and must have the correct role.

### What `hasAnyRole` and `hasRole` do

`hasAnyRole("AUTHOR", "ADMIN")` checks that the authenticated user's `GrantedAuthority` list contains `ROLE_AUTHOR` or `ROLE_ADMIN`. The `ROLE_` prefix is added automatically by Spring Security — you write `"AUTHOR"`, Spring looks for `"ROLE_AUTHOR"`.

This is populated from `User.getAuthorities()`:

```java
@Override
public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
}
```

### What `.anyRequest().authenticated()` means

"Any request that hasn't matched a rule above must at minimum have a valid authenticated user (any role)." This is the catch-all fallback and provides the fail-safe behaviour: unrecognised endpoints require auth rather than being silently public.

---

## Changes Made

### `config/SecurityConfig.java`

Added `import org.springframework.http.HttpMethod;`

Replaced:

```java
// BEFORE
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/v1/auth/**").permitAll()
    .requestMatchers("/api/v1/posts/**").permitAll()
    .anyRequest().authenticated()
)
```

With:

```java
// AFTER
.authorizeHttpRequests(auth -> auth
    // Auth — register, login, refresh are always public
    .requestMatchers("/api/v1/auth/**").permitAll()

    // Posts — reading published posts is public; all writes require a token + role
    .requestMatchers(HttpMethod.GET, "/api/v1/posts/**").permitAll()
    .requestMatchers(HttpMethod.POST, "/api/v1/posts/**").hasAnyRole("AUTHOR", "ADMIN")
    .requestMatchers(HttpMethod.PUT, "/api/v1/posts/**").hasAnyRole("AUTHOR", "ADMIN")
    .requestMatchers(HttpMethod.DELETE, "/api/v1/posts/**").hasAnyRole("AUTHOR", "ADMIN")

    // Categories — browsing is public; taxonomy management is ADMIN-only
    .requestMatchers(HttpMethod.GET, "/api/v1/categories/**").permitAll()
    .requestMatchers(HttpMethod.POST, "/api/v1/categories/**").hasRole("ADMIN")
    .requestMatchers(HttpMethod.PUT, "/api/v1/categories/**").hasRole("ADMIN")
    .requestMatchers(HttpMethod.DELETE, "/api/v1/categories/**").hasRole("ADMIN")

    // Tags — browsing is public; tag management is ADMIN-only
    .requestMatchers(HttpMethod.GET, "/api/v1/tags/**").permitAll()
    .requestMatchers(HttpMethod.POST, "/api/v1/tags/**").hasRole("ADMIN")
    .requestMatchers(HttpMethod.DELETE, "/api/v1/tags/**").hasRole("ADMIN")

    // Any future endpoint requires authentication by default
    .anyRequest().authenticated()
)
```

---

## How the Two Layers Work Together

```
Incoming HTTP request
        │
        ▼
┌─────────────────────────────────────────────┐
│         Spring Security Filter Chain         │
│                                             │
│  Is the path + method in permitAll?         │
│   → Yes: pass through (no token needed)     │
│                                             │
│  Does the path + method require a role?     │
│   → No valid JWT / wrong role: 401/403      │
│   → Valid JWT + correct role: pass through  │
└──────────────────┬──────────────────────────┘
                   │ request passes filter chain
                   ▼
┌─────────────────────────────────────────────┐
│            Controller method                 │
│                                             │
│  @PreAuthorize("hasAnyRole('AUTHOR','ADMIN')")│
│   → Checks role again (redundant but safe)  │
│                                             │
│  Service ownership check:                   │
│   → post.author.id == user.id || isAdmin    │
│   → Throws UnauthorizedException if not     │
└─────────────────────────────────────────────┘
```

The filter chain is the outer gate (coarse-grained: authenticated + right role category). `@PreAuthorize` + service is the inner gate (fine-grained: owns this specific resource).

---

## Testing Checklist

| What to test | How | Expected |
|---|---|---|
| Browse published posts without token | `GET /api/v1/posts` | 200 |
| Browse categories without token | `GET /api/v1/categories` | 200 |
| Browse tags without token | `GET /api/v1/tags` | 200 |
| Create post without token | `POST /api/v1/posts` | 401 |
| Update post without token | `PUT /api/v1/posts/1` | 401 |
| Delete post without token | `DELETE /api/v1/posts/1` | 401 |
| Publish post without token | `POST /api/v1/posts/1/publish` | 401 |
| Create category without token | `POST /api/v1/categories` | 401 |
| Update category without token | `PUT /api/v1/categories/1` | 401 |
| Delete category without token | `DELETE /api/v1/categories/1` | 401 |
| Create tag without token | `POST /api/v1/tags` | 401 |
| Delete tag without token | `DELETE /api/v1/tags/1` | 401 |
| Create post as SUBSCRIBER | Login as subscriber, `POST /api/v1/posts` | 403 |
| Create category as AUTHOR | Login as author, `POST /api/v1/categories` | 403 |
| Create post as AUTHOR | Login as author1@blog.com, `POST /api/v1/posts` | 201 |
| Create category as ADMIN | Login as admin@blog.com, `POST /api/v1/categories` | 201 |

---

## Key Lesson

> **`permitAll()` without an `HttpMethod` qualifier opens every verb on that path.** A wildcard like `requestMatchers("/api/v1/posts/**").permitAll()` looks like it just opens the read feed, but it also silently opens POST, PUT, and DELETE. Always qualify with `HttpMethod.GET` when you only intend to permit reads.
>
> The safest default is to list what is explicitly public, and let `.anyRequest().authenticated()` handle everything else.
