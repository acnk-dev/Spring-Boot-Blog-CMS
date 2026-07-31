# Blog CMS — Claude Code Project Instructions

## Project Overview

This is a Spring Boot 4.0.1 / Java 21 blog content management system with JWT auth, PostgreSQL, Flyway migrations, and role-based access control (SUBSCRIBER / AUTHOR / ADMIN). The primary purpose is to serve as a **teaching project** — every decision should be explainable and well-documented.

---

## Post-Commit Documentation Rule

**After every `git commit` you make in this project, you must generate a documentation file in `./docs/dev-docs/`.**

### What to generate

Create a Markdown file named after the commit (e.g., `commit-32-add-comment-entity.md`) that explains:

1. **Overview** — what was built and why, in plain English
2. **Problem / Motivation** — what gap or requirement triggered this work
3. **Decision** — for any non-trivial choice, document the options considered and why you chose the approach you did (trade-offs, rejected alternatives)
4. **Changes Made** — list each file changed with annotated code snippets showing before/after where relevant
5. **Concepts Introduced** — if a new Spring / Java concept was used (e.g., `@EntityGraph`, `@PreAuthorize`, Flyway versioning), explain it from first principles
6. **Testing Checklist** — what to manually verify or what tests cover this

Use the existing files in `docs/dev-docs/` as style reference — especially `commit-31-fix-user-entity-userdetails.md` for the depth and teaching tone expected.

### Naming convention

```
commit-<next-number>-<short-description>.md
```

Check the existing files to determine the next number. The files currently go up to `commit-31-*`.

### Do not skip this step

If you have just committed (i.e., the last tool call was a successful `git commit`), the very next action must be writing the documentation file. Do not ask the user if they want it — always generate it automatically.

---

## Spring Boot Backend Rules

### Architecture

Follow strict layered architecture. Dependencies only flow downward:

```
Controller → Service (interface) → ServiceImpl → Repository → Entity
```

- Controllers must never call repositories directly.
- Services must never reference `HttpServletRequest`, `HttpServletResponse`, or Spring MVC types.
- Repositories must never contain business logic — only query definitions.

### Naming Conventions

| Layer | Class suffix | Example |
|-------|-------------|---------|
| Controller | `Controller` | `PostController` |
| Service interface | `Service` | `PostService` |
| Service impl | `ServiceImpl` | `PostServiceImpl` |
| Repository | `Repository` | `PostRepository` |
| Entity | (none) | `Post` |
| Request DTO | `Request` | `CreatePostRequest` |
| Response DTO | `Response` | `PostResponse` |
| Mapper | `Mapper` | `PostMapper` |
| Exception | `Exception` | `ResourceNotFoundException` |

### Entities

- All entities use Lombok: `@Getter`, `@Setter`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`.
- Timestamps use `@CreationTimestamp` / `@UpdateTimestamp` — never set them manually.
- Soft deletes use an `isDeleted` boolean column, never `DELETE` statements for user-facing data.
- IDs are `Long` with `@GeneratedValue(strategy = GenerationType.IDENTITY)`.
- Never expose entities directly from controllers — always map to response DTOs.

### DTOs

- Separate `request/` and `response/` packages under `dto/`.
- Request DTOs use Bean Validation (`@NotBlank`, `@Email`, `@Size`, etc.) — never validate manually in service code.
- Response DTOs are plain POJOs (Lombok `@Data` or `@Getter`).
- Never put JPA annotations on DTOs.

### Services

- Service interfaces define the contract; `impl/` classes contain the implementation.
- All business rule violations throw a custom exception from `exception/` — never return `null` to signal failure.
- Use `@Transactional` on write operations in `ServiceImpl`.
- Ownership checks (a user may only edit their own content unless ADMIN) belong in the service, not the controller.

### Repositories

- Extend `JpaRepository<Entity, Long>`.
- Annotate all collection-returning methods that load associations with `@EntityGraph(attributePaths = {...})` to prevent N+1 queries.
- Atomic counter operations (view count, like count) use `@Modifying @Query("UPDATE ...")` — never load-modify-save in a loop.
- All queries must filter `isDeleted = false` unless intentionally fetching soft-deleted records.

### Security

- Never store passwords in plain text. Always use `BCryptPasswordEncoder` with strength 12.
- JWT secret must come from an environment variable (`JWT_SECRET`) in production — never hardcode.
- `@PreAuthorize` annotations go on controller methods, not service methods.
- `UserDetailsServiceImpl` must return the `entity.User` directly (it implements `UserDetails`) — do not wrap it in Spring Security's built-in `User` class.
- CSRF is disabled (stateless JWT API) — this is intentional.

### Exception Handling

- All exceptions bubble up to `GlobalExceptionHandler` (`@RestControllerAdvice`).
- Use the existing custom exception classes: `ResourceNotFoundException` (404), `BadRequestException` (400), `UnauthorizedException` (401), `FileStorageException` (500).
- Never return stack traces to clients.

### Database / Flyway

- Every schema change requires a new Flyway migration file: `V<n>__<description>.sql`.
- Migration files are immutable once committed — never edit a migration that has been applied.
- The naming format is `V<number>__<snake_case_description>.sql` (double underscore).
- `spring.jpa.hibernate.ddl-auto=validate` is intentional — Hibernate must not auto-create or modify tables.

### File Uploads

- All uploads go through `FileStorageService` (interface), currently implemented by `LocalFileStorageServiceImpl`.
- Validate file type and size before saving — use the `file.allowed-types` and `file.max-size` properties.
- Return a URL path to the stored file, not the absolute filesystem path.

### Code Quality

- No business logic in constructors or Lombok `@Builder` methods.
- No `System.out.println` — use SLF4J `@Slf4j` logger.
- No raw `@SuppressWarnings("unchecked")` without a comment explaining why.
- Keep controller methods thin: validate request → call service → return response.
- Mappers are pure functions (no side effects, no database calls).

---

## Running the Tester Agent

To run the tester agent against the current code, ask in the chat:

```
run the tester agent
```

Or open the Agents panel in the Claude Code UI and select `tester`. See the README for full details on what the tester checks.

---

## Project Conventions Summary

- Java 21, Spring Boot 4.0.1, Maven
- Database: PostgreSQL on port 5432, database name `blog-cms`
- App runs on port 8082
- Dev seeding activated with `-Dspring-boot.run.profiles=dev`
- Postman collection is in `docs/postman/`
- All dev-docs are in `docs/dev-docs/`