# Commit 4: Create User Entity and Role Enum

**Commit Hash:** `fa77ef6`
**Date:** January 17, 2026
**Commit Message:** Create User entity and Role enum

---

## Development Mindset & Decision Process

### The Foundation of Our Blog CMS

**Current State:**
- Application configured
- Database connection ready
- Flyway ready to run migrations
- But no data model yet!

**Goal:**
- Create our first entity: User
- Define role-based access control
- Set up database schema with Flyway
- Implement auditing (createdAt, updatedAt)
- Support soft deletes

**Teaching Point:** In any application, User is typically the first entity. Everything else (posts, comments, likes) relates back to users. We're building the foundation that everything else depends on.

---

## Decision 1: Role-Based Access Control (RBAC)

### Defining User Roles

```java
public enum Role {
    SUBSCRIBER,  // Free users - read posts, comment, like
    AUTHOR,      // Paid users - create posts, use AI (limited/unlimited)
    ADMIN        // Full access - manage users, moderate content
}
```

### Why These Three Roles?

**SUBSCRIBER (Free Tier)**
- **Who:** Anyone who signs up
- **Can do:** Read posts, comment, like, follow authors
- **Cannot do:** Create posts, access AI features
- **Business logic:** Entry point, converts to paid if they want to write

**AUTHOR (Paid Tier)**
- **Who:** Users who paid for writing access
- **Can do:** Everything SUBSCRIBER can do + create/edit/publish posts, use AI writing assistant
- **Cannot do:** Manage other users, delete others' content
- **Business logic:** Primary revenue source (subscription model)

**ADMIN (Platform Manager)**
- **Who:** Platform administrators (you, moderators)
- **Can do:** Everything + manage users, moderate all content, view analytics
- **Cannot do:** Nothing restricted (full access)
- **Business logic:** Internal role, not for sale

### Why Enum instead of String?

**Option 1: String (Bad)**
```java
private String role; // Can be "ADMIN", "admin", "Administrator", typos, etc.
```

**Problems:**
- Typos: "ADNIM" compiles but breaks at runtime
- Inconsistency: "ADMIN" vs "admin" vs "Admin"
- No IDE autocomplete
- No compile-time safety

**Option 2: Enum (Good)**
```java
private Role role; // Can only be Role.SUBSCRIBER, Role.AUTHOR, or Role.ADMIN
```

**Advantages:**
- **Type safety:** Invalid roles don't compile
- **Autocomplete:** IDE suggests valid options
- **Refactoring:** Rename all usages easily
- **Documentation:** Enum is self-documenting

**Teaching Point:** Use enums for fixed sets of values. If it's a dropdown in the UI, it should probably be an enum in the code.

### Why Not a Role Table?

**Some systems use:**
```sql
CREATE TABLE roles (id, name);
CREATE TABLE user_roles (user_id, role_id);
```

**We chose enum because:**
- **Simple:** Only 3 roles, unlikely to change
- **Performance:** No JOIN needed
- **Type safety:** Enum is checked at compile time
- **Sufficient:** We don't need dynamic roles

**When to use Role table:**
- Many roles (10+)
- Roles change frequently
- Need to add roles without deploying code
- Complex permission systems (RBAC with granular permissions)

**Teaching Point:** Don't over-engineer. Simple enum works for most applications. If you need more complexity later, you can refactor (but you probably won't need to).

---

## Decision 2: User Entity Design

### Complete User Entity

```java
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private Boolean emailVerified = false;

    @Column(length = 500)
    private String bio;

    @Column(length = 255)
    private String avatarUrl;

    @Column(nullable = false)
    private Boolean isDeleted = false;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
```

### Breaking Down Each Design Decision

#### 1. JPA Annotations

**@Entity**
- Marks class as JPA entity
- Hibernate will manage this class
- Table will be created (via Flyway in our case)

**@Table(name = "users")**
- Specifies table name in database
- Without this, default would be "user" (class name lowercase)
- "user" is often a reserved word in SQL, so we use "users"

**Teaching Point:** Always explicitly name your tables. Don't rely on defaults. "users" is conventional plural.

#### 2. Lombok Annotations (Reducing Boilerplate)

**@Getter / @Setter**
- Generates getters for all fields
- Generates setters for all fields
- Without Lombok: 20+ lines of boilerplate

**@NoArgsConstructor**
- Generates `public User() {}`
- Required by JPA (JPA needs to call `new User()` when loading from database)

**@AllArgsConstructor**
- Generates constructor with all fields: `new User(id, email, name, ...)`
- Useful for testing

**@Builder**
- Implements Builder pattern
- Cleaner object creation:
```java
User user = User.builder()
    .email("john@example.com")
    .name("John Doe")
    .role(Role.SUBSCRIBER)
    .build();
```

**Teaching Point:** Lombok saves ~50 lines per entity. Multiply by 10+ entities in a real project = hundreds of lines saved!

#### 3. Primary Key Strategy

```java
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;
```

**@Id** - Marks as primary key

**@GeneratedValue** - Value generated automatically

**IDENTITY Strategy:**
- Database generates ID (uses PostgreSQL's SERIAL or BIGSERIAL)
- ID assigned when row inserted
- Most common strategy for relational databases

**Other strategies:**
- `AUTO` - Let JPA decide (we prefer explicit)
- `SEQUENCE` - Use database sequence (PostgreSQL supports this)
- `TABLE` - Use a table to generate IDs (portable but slow)
- `UUID` - Use UUIDs instead of auto-increment (better for distributed systems)

**Why IDENTITY for us:**
- Simple and performant
- Standard for PostgreSQL
- Auto-increment is familiar to students

**Teaching Point:** For single-database applications, IDENTITY is perfect. For microservices or distributed systems, consider UUID.

#### 4. Email Field Design

```java
@Column(nullable = false, unique = true, length = 255)
private String email;
```

**nullable = false**
- Cannot be NULL in database
- Enforces at database level (in addition to application validation)

**unique = true**
- No two users can have same email
- Database enforces uniqueness
- JPA creates UNIQUE constraint

**length = 255**
- Maximum email length
- RFC 5321 standard: 254 characters max
- We use 255 for safety

**Why email as unique identifier?**
- Universal (everyone has one)
- Familiar to users
- Easy to remember (vs auto-generated usernames)
- Standard for authentication

**Teaching Point:** Always validate email format at application level, but database constraints are final safety net.

#### 5. Password Field

```java
@Column(nullable = false)
private String password;
```

**Important Security Notes:**

**What we'll store:**
- Hashed password (using BCrypt)
- Example: `$2a$10$N9qo8uLOickgx2ZMRZoMye...` (60 characters)

**What we'll NEVER store:**
- Plain text passwords
- Encrypted passwords (encryption is reversible)
- MD5 or SHA1 hashes (too weak)

**BCrypt characteristics:**
- One-way hashing (cannot reverse)
- Salt built-in (prevents rainbow table attacks)
- Slow by design (prevents brute force)
- Industry standard

**Teaching Point:** Password security is non-negotiable. Spring Security's BCryptPasswordEncoder handles this for us, but students must understand the concept.

#### 6. Name Field

```java
@Column(nullable = false, length = 100)
private String name;
```

**Why display name?**
- More personal than email
- Shows on blog posts, comments
- Better UX than email

**Length = 100:**
- Generous for most names
- Handles international names
- Prevents abuse (no 10,000 character names)

**Single "name" field vs separate "firstName" and "lastName":**
- **Single field:** Simpler, handles all cultures (some cultures don't have surname concept)
- **Separate fields:** Better for formal systems, government applications
- For a blog: single field is sufficient

#### 7. Role Field

```java
@Enumerated(EnumType.STRING)
@Column(nullable = false, length = 20)
private Role role;
```

**@Enumerated(EnumType.STRING)**
- Stores enum as string in database ("SUBSCRIBER", "AUTHOR", "ADMIN")
- Alternative: `EnumType.ORDINAL` (stores as 0, 1, 2)

**Why STRING over ORDINAL?**

**If we use ORDINAL:**
```java
enum Role {
    SUBSCRIBER,  // 0
    AUTHOR,      // 1
    ADMIN        // 2
}
```
Database stores: `0`, `1`, `2`

**Problem:** If we add a new role in the middle:
```java
enum Role {
    SUBSCRIBER,  // 0
    PREMIUM,     // 1  ← NEW ROLE
    AUTHOR,      // 2  ← CHANGED FROM 1!
    ADMIN        // 3  ← CHANGED FROM 2!
}
```
All existing roles break!

**With STRING:**
- Database stores: "SUBSCRIBER", "AUTHOR", "ADMIN"
- Adding new role: No impact on existing data
- Readable in database queries
- Slight storage overhead (acceptable trade-off)

**Teaching Point:** Always use EnumType.STRING for enums. The storage savings of ORDINAL aren't worth the maintenance risk.

#### 8. Email Verification

```java
@Column(nullable = false)
private Boolean emailVerified = false;
```

**Why verify emails?**
- Prevent fake accounts
- Ensure we can contact users
- Required for password resets

**Default = false:**
- New users start unverified
- Must click link in email to verify
- Can restrict features until verified

**Future logic:**
```java
if (!user.getEmailVerified()) {
    throw new UnverifiedEmailException("Please verify your email");
}
```

**Teaching Point:** Email verification is standard security practice. Don't skip it in real applications.

#### 9. Optional Fields (Bio, Avatar)

```java
@Column(length = 500)
private String bio;

@Column(length = 255)
private String avatarUrl;
```

**No `nullable = false` means:**
- These can be NULL
- Optional fields
- User can complete profile later

**Bio (500 characters):**
- Short author biography
- Shows on author page
- Twitter bio is 160 chars, we're generous with 500

**Avatar URL (not file upload):**
- We store URL, not the image itself
- Image hosted elsewhere (CDN, cloud storage)
- Example: `https://cdn.example.com/avatars/user123.jpg`

**Why URL not binary?**
- Database not designed for large blobs
- CDN delivers images faster
- Easier to scale
- Can use services like Cloudinary, AWS S3

**Teaching Point:** Don't store files in database. Store references (URLs) instead.

#### 10. Soft Delete Pattern

```java
@Column(nullable = false)
private Boolean isDeleted = false;
```

**Hard Delete (Permanent):**
```sql
DELETE FROM users WHERE id = 123;
-- User and all history GONE forever
```

**Soft Delete (Reversible):**
```sql
UPDATE users SET is_deleted = true WHERE id = 123;
-- User "deleted" but data preserved
```

**Why soft delete?**
1. **Recovery:** Restore accidentally deleted accounts
2. **Data integrity:** Foreign keys don't break (posts still reference user)
3. **Legal:** Some regulations require data retention
4. **Analytics:** Track churn, understand why users leave
5. **Audit trail:** Keep history for compliance

**How we'll use it:**
```java
// Don't fetch deleted users by default
@Query("SELECT u FROM User u WHERE u.isDeleted = false")
List<User> findAllActive();

// Soft delete
user.setIsDeleted(true);
userRepository.save(user);
```

**When to hard delete?**
- User requests data deletion (GDPR "right to be forgotten")
- After retention period (e.g., 90 days after soft delete)

**Teaching Point:** Soft delete is industry best practice. Hard delete only when legally required.

#### 11. Auditing Fields (Who and When)

```java
@CreationTimestamp
@Column(nullable = false, updatable = false)
private LocalDateTime createdAt;

@UpdateTimestamp
@Column(nullable = false)
private LocalDateTime updatedAt;
```

**@CreationTimestamp:**
- Hibernate automatically sets when entity created
- `updatable = false` - Never changes after creation
- Exact moment of account creation

**@UpdateTimestamp:**
- Hibernate automatically updates when entity modified
- Changes every time we save
- Last modification timestamp

**Why these are important:**
- **Debugging:** "When did this user register?"
- **Analytics:** User growth over time
- **Sorting:** "Newest users first"
- **Compliance:** Some regulations require audit trails

**What triggers @UpdateTimestamp?**
```java
user.setName("New Name");
userRepository.save(user);
// updatedAt automatically set to NOW
```

**Teaching Point:** Every entity should have createdAt/updatedAt. It's so common, we automate it with annotations.

---

## Decision 3: JPA Auditing Configuration

### Enabling Auditing

```java
@Configuration
@EnableJpaAuditing
public class JpaConfig {
    // Enables @CreationTimestamp and @UpdateTimestamp
}
```

**Why separate config class?**
- **Separation of concerns:** Configuration separate from business logic
- **Clarity:** Easy to find all JPA configuration
- **Convention:** Spring Boot best practice

**@Configuration:**
- Marks class as configuration
- Spring processes on startup

**@EnableJpaAuditing:**
- Activates JPA auditing features
- Without this, @CreationTimestamp/@UpdateTimestamp don't work
- Can also enable @CreatedBy/@ModifiedBy for "who changed this"

**Future enhancement:**
```java
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class JpaConfig {
    @Bean
    public AuditorAware<String> auditorProvider() {
        // Return currently logged-in user
        return () -> Optional.of(SecurityContextHolder.getContext()
            .getAuthentication().getName());
    }
}
```
This would add "createdBy" and "modifiedBy" fields automatically.

---

## Decision 4: Flyway Migration (V1__create_users_table.sql)

### The SQL Migration

```sql
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    name VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('SUBSCRIBER', 'AUTHOR', 'ADMIN')),
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    bio VARCHAR(500),
    avatar_url VARCHAR(255),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

### PostgreSQL-Specific Features Used

#### 1. BIGSERIAL
```sql
id BIGSERIAL PRIMARY KEY
```

**What it does:**
- Creates BIGINT column
- Auto-increment sequence
- PostgreSQL-specific (MySQL uses AUTO_INCREMENT)

**Range:**
- SERIAL: 1 to 2,147,483,647 (2 billion)
- BIGSERIAL: 1 to 9,223,372,036,854,775,807 (9 quintillion)

**Why BIGSERIAL?**
- Future-proof (won't run out of IDs)
- Matches Java's Long type
- Industry standard for new applications

#### 2. CHECK Constraint
```sql
role VARCHAR(20) NOT NULL CHECK (role IN ('SUBSCRIBER', 'AUTHOR', 'ADMIN'))
```

**Database-level validation:**
- Only allows these three values
- Prevents invalid data even if application has bugs
- Cannot insert role = 'INVALID'

**Teaching Point:** Defense in depth - validate at application AND database level.

#### 3. DEFAULT Values
```sql
email_verified BOOLEAN NOT NULL DEFAULT FALSE
created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
```

**DEFAULT FALSE:**
- If not specified, use false
- New users automatically unverified

**DEFAULT CURRENT_TIMESTAMP:**
- If not specified, use current time
- Backup if Hibernate doesn't set it

**Teaching Point:** Default values make inserts simpler and safer.

### Database Indexes

```sql
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_role ON users(role);
CREATE INDEX idx_users_is_deleted ON users(is_deleted);
```

**What indexes do:**
- Speed up queries with WHERE, ORDER BY, JOIN
- Trade-off: Slower writes, faster reads
- Like index in a book - jump to relevant page instead of reading everything

**Why index email?**
```sql
SELECT * FROM users WHERE email = 'john@example.com';
```
- Without index: Scan every row (slow for millions of users)
- With index: Direct lookup (O(log n) instead of O(n))
- Used for login (very frequent operation)

**Why index role?**
```sql
SELECT * FROM users WHERE role = 'AUTHOR';
```
- Common query: "Get all authors"
- Used for admin dashboard
- Small cardinality (only 3 values) but still useful

**Why index is_deleted?**
```sql
SELECT * FROM users WHERE is_deleted = false;
```
- Every query needs this (we don't show deleted users)
- Very common in WHERE clauses
- Essential for performance

**Teaching Point:** Index columns used in WHERE clauses. Don't over-index (each index slows writes), but don't under-index (slow queries). Profile and optimize.

---

## Decision 5: Documentation (lombok.md)

We created comprehensive Lombok documentation because:
- Students need reference for annotations
- Team members may not know Lombok
- Good documentation is professional practice

**Teaching Point:** Document your decisions, especially for tools that generate "magic" code.

---

## What Works Now After This Commit?

### Application Startup Sequence:

1. **Spring Boot starts**
2. **Database connects** (localhost:5432/blog-cms)
3. **Flyway runs:**
    - Creates `flyway_schema_history` table
    - Runs V1__create_users_table.sql
    - Creates `users` table with indexes
4. **Hibernate validates:**
    - Checks User entity matches users table
    - Validation passes ✓
5. **JPA Auditing enabled**
    - @CreationTimestamp/@UpdateTimestamp active
6. **Application ready** on port 8081

### We Can Now (Theoretically):

- Create User objects: `User user = User.builder().email(...).build();`
- Save to database: `userRepository.save(user);`
- Query users: `userRepository.findAll();`

### We Still Cannot:

- **No UserRepository created yet** (will create next)
- **No REST endpoints** (will create later)
- **No authentication** (will configure Security next)
- **Security blocks everything** (need to configure SecurityFilterChain)

---

## Teaching Talking Points

### For Students New to JPA/Hibernate:

"JPA is like a translator between Java objects and database tables:
- You write Java: `user.setName("John")`
- JPA writes SQL: `UPDATE users SET name = 'John' WHERE id = 1`
- You think in objects, database thinks in tables, JPA bridges the gap"

### For Students Coming from Other ORMs:

**Sequelize (Node.js):**
```javascript
const User = sequelize.define('User', {
  email: { type: DataTypes.STRING, unique: true },
  role: { type: DataTypes.ENUM('SUBSCRIBER', 'AUTHOR', 'ADMIN') }
});
```

**Django (Python):**
```python
class User(models.Model):
    email = models.EmailField(unique=True)
    role = models.CharField(choices=ROLE_CHOICES)
```

**Teaching Point:** Same concepts, different syntax. ORM patterns are universal.

### Common Questions:

**Q: Why not just write SQL?**
A: You can! But JPA provides:
- Type safety (compile-time errors)
- Less boilerplate (no manual mapping)
- Database portability (switch PostgreSQL to MySQL easier)
- Built-in caching
  Still, knowing SQL is essential - JPA generates SQL, you should understand it.

**Q: Should I always use soft delete?**
A: For user data, almost always yes. For temporary data (sessions, logs), hard delete is fine.

**Q: What if I need more than 3 roles?**
A: Add to enum: `MODERATOR`, `EDITOR`, etc. If you need many dynamic roles, then migrate to a roles table. Cross that bridge when you come to it.

**Q: Why LocalDateTime instead of Date?**
A: `Date` is old Java API (before Java 8). `LocalDateTime` is new, better, immutable, and more intuitive. Always use java.time package.

---

## Architectural Decisions Record

**ADR-004: Use Enum for Roles**
- Status: Accepted
- Context: Need role-based access control with 3 fixed roles
- Decision: Java enum with STRING persistence
- Consequences: Simple, type-safe, sufficient for our needs

**ADR-005: Implement Soft Delete**
- Status: Accepted
- Context: Need to "delete" users without losing data
- Decision: isDeleted boolean flag
- Consequences: Must filter in queries, can restore deleted users

**ADR-006: Use Flyway for Schema**
- Status: Accepted
- Context: Need to create database tables
- Decision: Flyway migration, not Hibernate auto-DDL
- Consequences: Schema in version control, production-safe

---

## Commit Stats
- **Files created:** 7
    - User.java entity (58 lines)
    - Role.java enum (7 lines)
    - JpaConfig.java (10 lines)
    - V1__create_users_table.sql (24 lines)
    - lombok.md documentation (307 lines)
- **Lines of code:** ~100 (excluding docs)
- **Tables created:** 1 (users)
- **Indexes created:** 3 (email, role, is_deleted)

---

## Next Steps

After this commit, we can:
1. **Create UserRepository** (Spring Data JPA interface)
2. **Create DTOs** (Data Transfer Objects for API requests/responses)
3. **Configure Security** (JWT authentication, SecurityFilterChain)
4. **Create AuthController** (Register, Login endpoints)
5. **Test authentication** (Postman/curl)

---

## Resources for Students
- JPA Annotations: https://www.baeldung.com/jpa-annotations
- Lombok Features: https://projectlombok.org/features/
- PostgreSQL Data Types: https://www.postgresql.org/docs/current/datatype.html
- Database Indexing: https://www.postgresql.org/docs/current/indexes.html
- Soft Delete Pattern: https://www.baeldung.com/spring-jpa-soft-delete