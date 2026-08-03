# Commit 41 — Async Email Service

## 1. Overview

This commit introduces a fully simulated asynchronous email service for the Blog
CMS.  Four new files form the core feature: an `EmailType` enum, an `EmailRequest`
DTO, an `EmailService` interface, and `EmailServiceImpl`.  A stub `Comment` entity
is added so the comment-notification method signature compiles.  A Flyway migration
(`V7`) creates the `comments` table so Hibernate's `ddl-auto=validate` does not
reject the new entity at startup.

`AuthServiceImpl` is updated to fire a welcome email after a user registers.
Because `sendWelcomeEmail` is `@Async`, the HTTP request thread returns its
response immediately — the 2-second simulated delay runs on a `blogcms-async-*`
background thread managed by the `ThreadPoolTaskExecutor` configured in commit 40.

A new tutorial file (`docs/tutorial/background-jobs-tutorial.md`) explains the
entire `@Async` mechanism from first principles, with Mermaid diagrams.

---

## 2. Why This Was Needed

Emails are inherently slow operations.  A real SMTP call can take hundreds of
milliseconds (or longer if the provider is congested).  Blocking the HTTP request
thread for that duration degrades throughput under any load.

Additionally, email delivery failure should never cause a registration to fail —
the user account exists regardless.  Decoupling email dispatch via `@Async` is the
correct architectural choice for this kind of side-effect.

The welcome email is also a pedagogical milestone: it demonstrates that the async
thread pool configured in commit 40 is wired into real application behaviour, not
just a configuration exercise.

---

## 3. Decisions Made

### 3.1 Simulated send vs real SMTP

- **Option A (real SMTP):** Add `spring-boot-starter-mail`, configure
  `spring.mail.*` properties, call `JavaMailSender`.  *Rejected because:* adds a
  hard external dependency (mail server) that breaks CI, risks sending emails to
  real addresses from dev seed data, and adds no teaching value at this stage.
- **Option B (chosen) — structured log + `Thread.sleep`:** Emit a structured log
  line containing recipient, subject, type, thread name, and timestamp.  Sleep 2
  seconds to mimic SMTP latency.  *Chosen because:* demonstrates all the important
  async behaviours (background thread, fire-and-forget, exception isolation) with
  zero infrastructure dependencies.  Swapping to real SMTP later requires only a
  new `SmtpEmailServiceImpl` — no callers change.

### 3.2 Placing `EmailType` and `EmailRequest` in `dto/` not `dto/request/`

`EmailRequest` is an internal DTO constructed by service code, not by Spring MVC
deserialising user input.  The `dto/request/` package convention is for
user-facing request bodies that carry `@Valid` Bean Validation annotations.
Placing an internal DTO there would be misleading.  The `dto/` root package is the
correct home for supporting types.

### 3.3 Stub `Comment` entity vs a String parameter

The task specifies `sendCommentNotification(User postAuthor, Comment comment)` as
the interface contract.  A real project will eventually build a full comment
feature; the entity belongs in the domain model now so the interface signature is
semantically correct.  Using a `String commentBody` parameter would create a
migration burden later (changing the interface signature breaks callers).

### 3.4 Where to catch exceptions in `EmailServiceImpl`

- **Option A — catch in `simulateSend` only:** Catches `InterruptedException` but
  not exceptions from building the `EmailRequest`.  *Rejected:* still possible for
  null pointer or other runtime exception to escape the public method.
- **Option B (chosen) — catch at top of every public method:** Each method wraps
  its entire body in `try/catch(Exception e)`.  *Chosen:* guarantees that no
  exception ever propagates out of an `@Async void` method regardless of where it
  originates.

---

## 4. New Concepts Introduced

### 4.1 `@Async` and Spring AOP proxies

`@EnableAsync` in `AsyncConfig` activates an aspect that wraps every Spring bean
whose methods carry `@Async` in a proxy.  When the proxy's method is called, it
submits the real method body to a thread pool and returns immediately to the
caller.

The caller must hold a reference to the *interface* (or Spring proxy), not `new
EmailServiceImpl()`.  In `AuthServiceImpl`, `private final EmailService emailService`
is injected by Spring — every call passes through the proxy.

### 4.2 `@Async("taskExecutor")` — named executor

`@Async` without a name falls back to Spring's `SimpleAsyncTaskExecutor`, which
creates a **new OS thread per call** — no pooling.  Naming the executor
(`"taskExecutor"`) routes every call to the bounded `ThreadPoolTaskExecutor`
configured in `AsyncConfig`.

### 4.3 `InterruptedException` handling

`Thread.sleep` throws `InterruptedException` if another thread calls
`Thread.interrupt()` on the sleeping thread.  Best practice after catching it is
to restore the interrupt flag (`Thread.currentThread().interrupt()`) so any code
higher up the call stack can detect that the thread was interrupted.  Then return
early — retrying the sleep would suppress the interrupt signal.

### 4.4 Flyway migration for `comments` table

`spring.jpa.hibernate.ddl-auto=validate` means Hibernate compares its entity model
to the actual database schema at startup and throws if they differ.  A new JPA
entity without a matching table causes a startup failure.  `V7__create_comments_table.sql`
creates the table before the application starts.

---

## 5. Files Changed

### `src/main/java/com/studylink/blogcms/dto/EmailType.java` (new)

Enum with four values: `WELCOME`, `PASSWORD_RESET`, `COMMENT_NOTIFICATION`,
`WEEKLY_DIGEST`.  Lives in `dto/` because it is a domain-level type, not a layer
artifact.

### `src/main/java/com/studylink/blogcms/dto/EmailRequest.java` (new)

Internal DTO: `to`, `subject`, `body`, `type`.  Lombok `@Getter @Builder
@NoArgsConstructor @AllArgsConstructor`.  No Bean Validation — not user-facing.

### `src/main/java/com/studylink/blogcms/service/EmailService.java` (new)

Interface with four method signatures.  Importing `Comment`, `Post`, `User` from
`entity/` keeps the contract semantically rich.

### `src/main/java/com/studylink/blogcms/service/impl/EmailServiceImpl.java` (new)

Key excerpt — the pattern every public method follows:

```java
@Override
@Async("taskExecutor")
public void sendWelcomeEmail(User user) {
    try {
        EmailRequest request = EmailRequest.builder()
                .to(user.getEmail())
                .subject("Welcome to Blog CMS, " + user.getName() + "!")
                .body("Hi " + user.getName() + ",\n\nYour account has been created successfully.")
                .type(EmailType.WELCOME)
                .build();

        log.info("[EMAIL] Preparing WELCOME email for user id={} name={}", user.getId(), user.getName());
        simulateSend(request);  // Thread.sleep(2000) + structured log
    } catch (Exception e) {
        log.error("[EMAIL] Failed to send WELCOME email to {}: {}", user.getEmail(), e.getMessage(), e);
    }
}
```

### `src/main/java/com/studylink/blogcms/entity/Comment.java` (new)

Minimal entity: `id`, `post` (ManyToOne), `author` (ManyToOne), `body`, `isDeleted`,
`createdAt`, `updatedAt`.  Follows the standard entity template.

### `src/main/resources/db/migrations/V7__create_comments_table.sql` (new)

```sql
CREATE TABLE comments (
    id         BIGSERIAL PRIMARY KEY,
    post_id    BIGINT    NOT NULL REFERENCES posts(id),
    user_id    BIGINT    NOT NULL REFERENCES users(id),
    body       TEXT      NOT NULL,
    is_deleted BOOLEAN   NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_comments_post_id ON comments(post_id);
CREATE INDEX idx_comments_user_id ON comments(user_id);
```

### `src/main/java/com/studylink/blogcms/service/impl/AuthServiceImpl.java` (modified)

Before:
```java
private final UserService userService;
private final PasswordEncoder passwordEncoder;
private final JwtUtil jwtUtil;
```

After:
```java
private final UserService userService;
private final PasswordEncoder passwordEncoder;
private final JwtUtil jwtUtil;
private final EmailService emailService;  // added
```

Before (inside `register`):
```java
user = userService.createUser(user);
return buildAuthResponse(user);
```

After:
```java
User savedUser = userService.createUser(user);
emailService.sendWelcomeEmail(savedUser);   // fire-and-forget
return buildAuthResponse(savedUser);
```

### `docs/tutorial/background-jobs-tutorial.md` (new)

Full `@Async` tutorial with Mermaid diagrams covering proxy mechanics, self-invocation,
return types, error handling, testing patterns, and a common pitfalls table.

---

## 6. Testing Checklist

| What to test | How to test | Expected result |
|---|---|---|
| Welcome email fires after registration | POST `/api/v1/auth/register` with a new email | HTTP 200 response returns immediately; `[EMAIL] Preparing WELCOME email` appears in logs within ~0 ms; `[EMAIL] Sent successfully` appears ~2 seconds later |
| Email runs on background thread | Check thread name in log | Log line shows `thread=blogcms-async-1` (or similar prefix), never the Tomcat thread `http-nio-*` |
| Registration succeeds even if email logging were to throw | Temporarily introduce a log-line that throws; confirm registration still returns 200 | The `catch(Exception e)` in `sendWelcomeEmail` absorbs the error; registration is unaffected |
| Weekly digest logs all post titles | Call `sendWeeklyDigest` in a unit test with a list of 3 posts | Log line contains all three titles in `posts=[...]` field |
| Flyway V7 migration applies cleanly | Restart application against a fresh database | Application starts; `comments` table exists; Hibernate validate passes |
