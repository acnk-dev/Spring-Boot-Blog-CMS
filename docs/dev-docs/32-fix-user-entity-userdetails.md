# Commit 31 — Fix: User Entity Implements UserDetails

## Overview

All write endpoints (`createPost`, `updatePost`, `deletePost`, `publishPost`, `uploadFeaturedImage`) were throwing a `NullPointerException` at runtime because `@AuthenticationPrincipal User user` was always injecting `null`. The root cause was that `UserDetailsServiceImpl` stored a Spring Security built-in `User` object in the `SecurityContext` instead of the application's own `entity.User`. This commit fixes the mismatch by making `entity.User` implement `UserDetails` and simplifying `UserDetailsServiceImpl` to return the entity directly.

---

## Bug Report

```
ERROR — POST /api/v1/posts
java.lang.NullPointerException:
  Cannot invoke "com.studylink.blogcms.entity.User.getEmail()"
  because "user" is null
  at PostController.createPost(PostController.java:82)
```

**Symptom:** 500 Internal Server Error on any authenticated write endpoint.

**Observed vs expected:**

| | Expected | Actual |
|---|---|---|
| `@AuthenticationPrincipal User user` | Injected `entity.User` from SecurityContext | `null` |
| `user.getEmail()` | Returns user's email string | NullPointerException |

---

## Root Cause Analysis

### The type mismatch

`UserDetailsServiceImpl` had two methods — `loadUserByUsername` and `loadUserById` — both of which built their return value using the Spring Security builder:

```java
// BEFORE — returning a Spring Security built-in object
return org.springframework.security.core.userdetails.User.builder()
        .username(user.getEmail())
        .password(user.getPassword())
        .authorities("ROLE_" + user.getRole().name())
        .build();
```

This produces a `org.springframework.security.core.userdetails.User` instance — Spring Security's own class. That object is what gets stored as the `principal` inside the `Authentication` object in the `SecurityContext`.

When Spring resolves `@AuthenticationPrincipal User user` in the controller, it calls `Authentication.getPrincipal()` and tries to cast the result to `com.studylink.blogcms.entity.User`. The types are unrelated — one is a Spring Security internal class, the other is a JPA entity — so the cast fails silently and `null` is injected.

### Why it fails silently (not with a ClassCastException)

`@AuthenticationPrincipal` does not throw when types don't match. It returns `null` and lets the controller method run. The NPE only surfaces on the first line that dereferences the null reference (`user.getEmail()`), which makes the error appear to be a controller bug rather than a security configuration bug.

### Why only write endpoints were affected

Public (read) endpoints don't use `@AuthenticationPrincipal` at all — they have no `user` parameter. This is why `getAllPosts`, `getPostById`, `getPostBySlug`, etc. worked fine. Only the endpoints that needed the current user's identity were broken.

### Why @PreAuthorize didn't block the request

`@PreAuthorize("hasAnyRole('AUTHOR', 'ADMIN')")` checks roles against the `Authentication` object's `GrantedAuthority` list, which was correctly populated from the JWT. The role check passed because the authority string `"ROLE_AUTHOR"` was present. The problem was only with the principal *type* — the roles were right, but the object holding them was the wrong class.

---

## Decision: Make `entity.User` Implement `UserDetails`

### Options considered

**Option A — Keep Spring Security's built-in User; extract the entity in each controller**

In every write endpoint, inject `@AuthenticationPrincipal UserDetails principal`, then load the `User` entity from `UserRepository` using `principal.getUsername()` (email).

```java
// would have looked like this in every controller method:
@AuthenticationPrincipal UserDetails principal,
// then inside:
User user = userRepository.findByEmail(principal.getUsername()).orElseThrow(...);
```

*Rejected because:* Adds a `userRepository` dependency to every controller. Each request triggers an extra database query just to fetch what should already be available. Pollutes controller methods with boilerplate. Every future controller would need the same pattern.

---

**Option B — Create a `UserPrincipal` wrapper class**

Create a dedicated `UserPrincipal` class that implements `UserDetails` and holds a reference to the `entity.User`. Change `UserDetailsServiceImpl` to return `UserPrincipal`. Change all controller signatures to `@AuthenticationPrincipal UserPrincipal principal` and call `principal.getUser()`.

*Rejected because:* Requires changing every controller method signature. Adds an extra class that exists purely as a structural workaround. `UserPrincipal.getUser()` is essentially just `entity.User` in disguise, so the extra indirection buys nothing.

---

**Option C — Make `entity.User` implement `UserDetails` (chosen)**

Add `implements UserDetails` to the `User` entity. Implement the required methods directly on the entity. Change `UserDetailsServiceImpl` to return the `User` entity from the repository — no builder needed.

*Chosen because:*
- Zero changes to any controller — `@AuthenticationPrincipal User user` works as-is everywhere.
- `UserDetailsServiceImpl` becomes simpler, not more complex.
- No extra classes, no extra queries, no boilerplate.
- This is the most common pattern in Spring Boot applications and is well-understood by anyone reading the code.

---

## What `UserDetails` Requires

`UserDetails` (from `org.springframework.security.core.userdetails`) is a Spring Security interface that defines the contract for a principal object. It has the following methods:

| Method | Return type | Implementation in `User` |
|---|---|---|
| `getAuthorities()` | `Collection<? extends GrantedAuthority>` | Built from `role` field: `ROLE_AUTHOR`, `ROLE_ADMIN`, etc. |
| `getPassword()` | `String` | Already satisfied by Lombok `@Getter` on the `password` field |
| `getUsername()` | `String` | Returns `email` (email is the login identity in this app) |
| `isEnabled()` | `boolean` | Returns `!isDeleted` — soft-deleted users are disabled |
| `isAccountNonExpired()` | `boolean` | Default `true` in Spring Security 6 — not overridden |
| `isAccountNonLocked()` | `boolean` | Default `true` in Spring Security 6 — not overridden |
| `isCredentialsNonExpired()` | `boolean` | Default `true` in Spring Security 6 — not overridden |

### Why `getPassword()` doesn't need an `@Override`

Lombok's `@Getter` generates `getPassword()` from the `password` field. The generated method has exactly the signature that `UserDetails` requires (`public String getPassword()`), so it satisfies the interface contract automatically. No manual override is needed.

### Why `isAccountNonExpired`, `isAccountNonLocked`, `isCredentialsNonExpired` are not overridden

Spring Security 6 introduced default implementations for these three methods — all returning `true`. Since the Blog CMS does not currently implement account expiry, lockout, or credential expiry, there is nothing to override. If these features are added later (e.g., locking accounts after N failed logins), override the relevant method at that point.

### Why `getUsername()` returns email, not name

Spring Security uses the value from `getUsername()` as the unique identifier for the principal — it is stored in the JWT subject claim and used to reload the user from the database. Email is unique per user (enforced by the database constraint). The user's display name (`name` field) is not unique and must not be used here.

---

## Changes Made

### `entity/User.java`

```java
// Added imports
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import java.util.Collection;
import java.util.List;

// Class declaration
public class User implements UserDetails {

    // ... existing fields unchanged ...

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getUsername() {
        return email;
    }

    // getPassword() satisfied by Lombok @Getter

    @Override
    public boolean isEnabled() {
        return !isDeleted;
    }
}
```

### `security/UserDetailsServiceImpl.java`

```java
// BEFORE
@Override
public UserDetails loadUserByUsername(String email) {
    User user = userRepository.findByEmailAndIsDeletedFalse(email)
            .orElseThrow(() -> new UsernameNotFoundException(...));

    return org.springframework.security.core.userdetails.User.builder()
            .username(user.getEmail())
            .password(user.getPassword())
            .authorities("ROLE_" + user.getRole().name())
            .accountExpired(false)
            .accountLocked(false)
            .credentialsExpired(false)
            .disabled(user.getIsDeleted())
            .build();  // ← returns Spring's built-in User, not entity.User
}

// AFTER
@Override
public UserDetails loadUserByUsername(String email) {
    return userRepository.findByEmailAndIsDeletedFalse(email)
            .orElseThrow(() -> new UsernameNotFoundException(...));
    // ← returns entity.User directly; it now implements UserDetails
}
```

The same simplification applies to `loadUserById`. No controllers were changed.

---

## How `@AuthenticationPrincipal` Now Works

```
HTTP Request
  └─ Authorization: Bearer <jwt>
       │
       ▼
JwtAuthenticationFilter
  └─ Extracts user ID from JWT claims
  └─ Calls UserDetailsServiceImpl.loadUserById(userId)
       └─ Returns entity.User (which now implements UserDetails)
  └─ Stores entity.User as principal in SecurityContext
       │
       ▼
Spring MVC — controller method resolution
  └─ Sees @AuthenticationPrincipal User user
  └─ Calls Authentication.getPrincipal()
       └─ Returns entity.User ✓ (types match — no null)
  └─ Injects entity.User into the method parameter
       │
       ▼
Controller method runs
  └─ user.getId()     ✓
  └─ user.getEmail()  ✓
  └─ user.getRole()   ✓
```

---

## Side Effects and Considerations

### JPA serialization

Adding `implements UserDetails` adds methods to the class but no new `@Column` fields. JPA only maps fields annotated with `@Column`, `@Id`, etc. The new interface methods have no impact on the database schema or Flyway migrations.

### Lombok compatibility

Lombok's `@Getter`, `@Setter`, `@Builder`, `@NoArgsConstructor`, and `@AllArgsConstructor` all operate on fields. Adding interface methods does not interfere with any Lombok annotation. The builder pattern continues to work exactly as before.

### `@AllArgsConstructor` and the new methods

`@AllArgsConstructor` generates a constructor for all *fields*. The `UserDetails` methods are not fields, so the constructor signature is unchanged. Existing code that constructs `User` objects (e.g., in `AuthService`) is unaffected.

### Spring Security's password encoding

`getPassword()` returns the bcrypt-encoded password hash stored in the database. Spring Security uses this value internally during authentication to compare against the submitted plaintext password. The hash is never sent to the client — the `UserResponse` DTO does not include the password field.

---

## Testing Checklist

After this fix, verify all authenticated endpoints work end-to-end:

| Endpoint | Role required | What to verify |
|---|---|---|
| `POST /api/v1/posts` | AUTHOR or ADMIN | Returns 201, `user` is non-null, email logged correctly |
| `PUT /api/v1/posts/{id}` | AUTHOR or ADMIN | Returns 200, ownership check works |
| `DELETE /api/v1/posts/{id}` | AUTHOR or ADMIN | Returns 204, ownership check works |
| `POST /api/v1/posts/{id}/publish` | AUTHOR or ADMIN | Returns 200, author check works |
| `POST /api/v1/posts/{id}/image` | AUTHOR or ADMIN | Returns 200, image uploaded correctly |
| `POST /api/v1/categories` | ADMIN | Returns 201 |
| `PUT /api/v1/categories/{id}` | ADMIN | Returns 200 |
| `DELETE /api/v1/categories/{id}` | ADMIN | Returns 204 |
| `POST /api/v1/tags` | ADMIN | Returns 201 |
| `DELETE /api/v1/tags/{id}` | ADMIN | Returns 204 |

Also verify that public endpoints are unaffected:

| Endpoint | Expected |
|---|---|
| `GET /api/v1/posts` | 200, no auth required |
| `GET /api/v1/posts/{id}` | 200, no auth required |
| `GET /api/v1/categories` | 200, no auth required |
| `GET /api/v1/tags` | 200, no auth required |

---

## Key Lesson

> **`@AuthenticationPrincipal` matches by type, not by content.** If the object stored in the `SecurityContext` is not assignment-compatible with the declared parameter type, Spring injects `null` — no exception, no warning. The bug then surfaces as an NPE one line later, which misdirects debugging toward the controller rather than the security configuration.
>
> The rule: whatever type `UserDetailsService.loadUser*()` returns is the type that must be declared in `@AuthenticationPrincipal`. Make them the same class and the problem cannot occur.
