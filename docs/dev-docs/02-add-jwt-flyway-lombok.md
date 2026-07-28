# Commit 2: Add JWT, Flyway, and Lombok Dependencies

**Commit Hash:** `fde2dec`
**Date:** January 17, 2026
**Commit Message:** Add JWT, Flyway, and Lombok dependencies

---

## Development Mindset & Decision Process

### The Problem We're Solving
We have a basic Spring Boot app, but we need:
1. **Authentication mechanism** - How do users prove who they are?
2. **Database migration system** - How do we manage database schema changes?
3. **Cleaner code** - How do we reduce boilerplate Java code?

### Strategic Thinking

**Question:** Why add these dependencies NOW, before writing any code?

**Answer:**
- **Dependency Lock-in:** It's easier to choose technologies upfront than to refactor later
- **Architecture decisions:** JWT vs Sessions affects our entire auth strategy
- **Developer experience:** Lombok affects how we write every entity/DTO
- **Database strategy:** Flyway affects how we create tables and manage schema

**Teaching Point:** In real projects, you'd do a "technical spike" - research options, discuss with team, make informed decisions. We're documenting those decisions here.

---

## Decision 1: JWT for Authentication

### Why JWT over Session-Based Auth?

**Session-Based Authentication (Traditional):**
```
User logs in → Server creates session → Stores in memory/Redis
User requests → Server checks session storage → Allows/Denies
```

**Problems with Sessions:**
- Server must store session state (not stateless)
- Hard to scale horizontally (multiple servers need shared session storage)
- CORS complications for separate frontend/backend
- Mobile apps harder to implement

**JWT Authentication (Token-Based):**
```
User logs in → Server creates JWT token → Returns to client
User requests → Client sends token → Server verifies signature → Allows/Denies
```

**Advantages for our Blog CMS:**
- **Stateless:** Server doesn't store anything (scales easily)
- **Perfect for REST APIs:** Frontend can be separate (React/Vue/Angular)
- **Mobile-friendly:** Same token works for web and mobile apps
- **Microservices-ready:** Multiple services can verify the same token
- **Industry standard:** Most modern APIs use JWT

**Teaching Point:** JWT is not "better" than sessions universally - it's better for our use case (REST API with potential mobile app). For a traditional server-rendered web app, sessions might be simpler.

### Why 3 JWT Libraries?

```xml
jjwt-api (0.12.3)       - Interface definitions
jjwt-impl (0.12.3)      - Implementation
jjwt-jackson (0.12.3)   - JSON serialization
```

**Analogy for Students:**
- `jjwt-api` = The menu at a restaurant (interface)
- `jjwt-impl` = The kitchen that makes the food (implementation)
- `jjwt-jackson` = The waiter who brings the food (serializer)

**Why separate them?**
1. **Modularity:** Could swap Jackson for Gson if needed
2. **Security:** Implementation hidden behind interface
3. **Industry pattern:** Same pattern as SLF4J (logging) uses

**Version Choice (0.12.3):**
- Latest stable version
- Supports modern JWT standards (RS256, ES256, etc.)
- Good documentation and active maintenance

---

## Decision 2: Flyway for Database Migrations

### The Database Schema Problem

**Without Flyway:**
```
Developer 1: Creates users table manually
Developer 2: Pulls code, app crashes (no table!)
Developer 2: Creates users table with different structure
Production: Which version should we use?
```

**With Flyway:**
```
Developer 1: Creates V1__create_users.sql
Developer 2: Pulls code, Flyway runs migration automatically
Production: Flyway runs same migration
Everyone has identical schema!
```

### How Flyway Works

```
Application startup →
Flyway checks database →
Creates flyway_schema_history table →
Scans db/migrations/ folder →
Runs new migrations in version order →
Records what ran →
Application starts
```

**Migration File Naming Convention:**
```
V1__create_users_table.sql      ← Version 1
V2__add_posts_table.sql         ← Version 2
V3__add_categories.sql          ← Version 3
```

**Rules:**
- `V` = Versioned migration
- `1` = Version number (sequential)
- `__` = Two underscores separator
- `description` = Human-readable description
- `.sql` = SQL file

**Teaching Point:** This is "database version control" - just like Git tracks code changes, Flyway tracks schema changes.

### Why Flyway over Liquibase?

**Both are excellent. We chose Flyway because:**
- Simpler to learn (plain SQL files)
- Better Spring Boot integration
- Lighter weight (less XML configuration)
- Sufficient for our needs

**Liquibase advantages we don't need:**
- Database-agnostic XML (we're committed to PostgreSQL)
- Rollback support (we'll handle carefully with forward migrations)

**Teaching Point:** Don't choose the most powerful tool - choose the right tool for your needs.

### Flyway PostgreSQL Support

We added `flyway-database-postgresql` separately because:
- Flyway core is database-agnostic
- PostgreSQL-specific support handles PostgreSQL quirks
- Better error messages for PostgreSQL issues

---

## Decision 3: Lombok for Reducing Boilerplate

### The Java Verbosity Problem

**Without Lombok:**
```java
public class User {
    private Long id;
    private String email;
    private String name;

    public User() {}

    public User(Long id, String email, String name) {
        this.id = id;
        this.email = email;
        this.name = name;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @Override
    public boolean equals(Object o) { /* 15 lines */ }

    @Override
    public int hashCode() { /* 5 lines */ }

    @Override
    public String toString() { /* 5 lines */ }
}
// Total: ~50 lines for 3 fields!
```

**With Lombok:**
```java
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    private Long id;
    private String email;
    private String name;
}
// Total: 9 lines for same functionality!
```

### Lombok Annotations We'll Use

**@Getter/@Setter**
- Generates getters and setters
- Can be placed on class (all fields) or individual fields

**@NoArgsConstructor**
- Generates empty constructor
- Required by JPA for entity instantiation

**@AllArgsConstructor**
- Generates constructor with all fields
- Useful for testing and builder pattern

**@Builder**
- Implements builder pattern
- Cleaner object creation: `User.builder().name("John").email("john@example.com").build()`

**@ToString** (will use later)
- Generates readable toString()
- Helpful for logging and debugging

**@EqualsAndHashCode** (will use later)
- Generates equals() and hashCode()
- Important for collections and JPA

### Lombok Controversy

**Why some developers dislike Lombok:**
- "Magic" code generation at compile time
- IDE plugins required (though most IDEs support it now)
- Debugging can be trickier
- Hides implementation details

**Why we're using it:**
- Industry standard (used by most Spring Boot projects)
- Massive productivity boost
- Less code = fewer bugs
- Spring Framework team endorses it
- Students should be familiar with it for job market

**Teaching Point:** Lombok is a tool. Like any tool, it can be misused. We'll use it responsibly - only for standard patterns (getters/setters/constructors), not for complex logic.

---

## Documentation Created: packages-used.md

**Why create this document?**
- Students will ask "What does this dependency do?"
- Future developers (or you in 6 months!) need context
- Good documentation is part of good code

**Teaching Point:** Professional projects have docs/ folders. Get in the habit early!

---

## What Changed in This Commit?

### Files Modified:
1. **pom.xml** - Added 6 new dependencies
2. **docs/packages-used.md** - Created comprehensive documentation

### Impact on Project:

**Can now:**
- Generate JWT tokens (when we write the code)
- Create database migrations (when we write SQL files)
- Use Lombok annotations (when we create entities)

**Still cannot:**
- Actually run database migrations (database not configured)
- Create tokens (no JWT service written yet)
- Connect to database (no connection settings)

**Next steps:**
1. Configure application.properties for database connection
2. Set up Flyway migration paths
3. Create first entity with Lombok annotations

---

## Teaching Talking Points

### For Students New to Dependencies:
"Think of dependencies like ingredients for a recipe. We're gathering all ingredients before we start cooking. JWT, Flyway, and Lombok are tools we'll use throughout the project."

### For Students Worried About "Too Many Libraries":
"Enterprise Java development involves choosing best-of-breed libraries. This is normal. Python uses pip, JavaScript uses npm - Java uses Maven to manage dependencies. Each library has a specific job."

### Common Questions:

**Q: Could we use Spring Security's built-in authentication instead of JWT?**
A: Yes, but Spring Security's default is session-based. JWT gives us more flexibility for REST APIs and mobile apps.

**Q: Why not use JPA's schema generation instead of Flyway?**
A: JPA can generate schemas, but:
- Not safe for production (can drop tables!)
- No version history
- No team collaboration on schema changes
- Flyway is the industry standard for production apps

**Q: What if Lombok breaks in future Java versions?**
A: Lombok maintainers are quick to support new Java versions. Worst case, IntelliJ can "delombok" (convert Lombok code to plain Java). But this is unlikely - Lombok is battle-tested and widely used.

---

## Architectural Decisions Record (ADR)

This commit essentially creates 3 ADRs:

1. **ADR-001: Use JWT for Authentication**
    - Status: Accepted
    - Context: Need stateless authentication for REST API
    - Decision: JJWT library version 0.12.3
    - Consequences: Must implement token generation/validation service

2. **ADR-002: Use Flyway for Database Migrations**
    - Status: Accepted
    - Context: Need version control for database schema
    - Decision: Flyway over Liquibase for simplicity
    - Consequences: Must write SQL migrations for schema changes

3. **ADR-003: Use Lombok for Boilerplate Reduction**
    - Status: Accepted
    - Context: Java verbosity hurts productivity
    - Decision: Lombok for standard patterns only
    - Consequences: All developers need IDE plugin

**Teaching Point:** In professional settings, you'd document these formally as ADRs. We're doing it in commit docs, which serves a similar purpose.

---

## Commit Stats
- **Files modified:** 2 (pom.xml, new documentation)
- **Dependencies added:** 6 (3 for JWT, 2 for Flyway, 1 for Lombok)
- **Lines added:** 115
- **Decision impact:** High (affects entire architecture)

---

## Resources for Students
- JWT Introduction: https://jwt.io/introduction
- Flyway Documentation: https://flywaydb.org/documentation/
- Lombok Features: https://projectlombok.org/features/
- JJWT GitHub: https://github.com/jwtk/jjwt