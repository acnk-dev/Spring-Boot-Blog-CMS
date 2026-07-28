# Commit 5: Create UserRepository Interface

**Date:** January 28, 2026
**Commit Message:** Create UserRepository interface

---

## Development Mindset & Decision Process

### The Data Access Layer

**Current State:**
- User entity exists and maps to database table
- JPA/Hibernate configured
- Database connection ready
- But no way to query or manipulate user data!

**Goal:**
- Create repository interface for User entity
- Define query methods for common operations
- Handle soft delete filtering
- Support search functionality
- Follow Spring Data JPA best practices

**Teaching Point:** The Repository pattern separates data access logic from business logic. Instead of writing SQL or JDBC code everywhere, we define an interface and Spring Data JPA implements it automatically at runtime.

---

## Decision 1: Spring Data JPA Repository Pattern

### What is Spring Data JPA?

**Traditional Approach (Without Spring Data):**
```java
@Component
public class UserDao {
    @PersistenceContext
    private EntityManager entityManager;

    public User findById(Long id) {
        return entityManager.find(User.class, id);
    }

    public List<User> findAll() {
        return entityManager.createQuery("SELECT u FROM User u", User.class)
            .getResultList();
    }

    public User save(User user) {
        if (user.getId() == null) {
            entityManager.persist(user);
            return user;
        } else {
            return entityManager.merge(user);
        }
    }

    public void delete(User user) {
        entityManager.remove(user);
    }

    // ...50+ more lines for basic CRUD
}
```

**Spring Data JPA Approach:**
```java
public interface UserRepository extends JpaRepository<User, Long> {
    // That's it! All basic CRUD operations are already implemented.
}
```

**What You Get for Free:**
```java
userRepository.save(user);           // Insert or update
userRepository.findById(1L);         // Find by primary key
userRepository.findAll();            // Find all users
userRepository.delete(user);         // Delete user
userRepository.count();              // Count total users
userRepository.existsById(1L);       // Check if exists
// ...and 20+ more methods!
```

**Teaching Point:** Spring Data JPA uses "convention over configuration." By extending `JpaRepository<User, Long>`, we inherit all standard CRUD operations. We only define methods for custom queries.

### Why Interface, Not Class?

**Students often ask:** "How can we use an interface? Where's the implementation?"

**Answer:**
1. Spring Data JPA generates the implementation at runtime
2. Uses Java Reflection and Proxy pattern
3. At application startup, Spring creates a class like this:

```java
// Spring generates this automatically (simplified version)
public class UserRepositoryImpl implements UserRepository {
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Optional<User> findByEmail(String email) {
        return entityManager
            .createQuery("SELECT u FROM User u WHERE u.email = :email", User.class)
            .setParameter("email", email)
            .getResultStream()
            .findFirst();
    }
    // ... all other methods
}
```

4. Spring injects this implementation wherever we use `@Autowired UserRepository`

**Teaching Point:** This is "magic" but good magic. It eliminates boilerplate. The trade-off: you must understand naming conventions and limitations.

---

## Decision 2: Method Naming Conventions (Derived Queries)

### How Spring Data JPA Understands Method Names

Spring Data JPA parses method names and generates queries automatically.

**Pattern:**
```
find + By + Property + Condition + And/Or + Property2 + Condition2
```

### Examples from Our Repository

#### 1. Simple Equality
```java
Optional<User> findByEmail(String email);
```

**Generated Query:**
```sql
SELECT * FROM users WHERE email = ?
```

**Naming Convention Breakdown:**
- `find` - Operation (could be `get`, `read`, `query`)
- `By` - Separator
- `Email` - Property name (matches `User.email`)

#### 2. Multiple Conditions with AND
```java
Optional<User> findByEmailAndIsDeletedFalse(String email);
```

**Generated Query:**
```sql
SELECT * FROM users WHERE email = ? AND is_deleted = false
```

**Breakdown:**
- `Email` - First property
- `And` - Logical operator
- `IsDeleted` - Second property (camelCase: isDeleted)
- `False` - Condition (value to compare)

**Teaching Point:** Notice how `IsDeletedFalse` becomes `is_deleted = false`. Spring understands boolean properties and conditions like `True`, `False`.

#### 3. Boolean Checks
```java
boolean existsByEmail(String email);
```

**Generated Query:**
```sql
SELECT COUNT(*) > 0 FROM users WHERE email = ?
```

**Breakdown:**
- `exists` - Returns boolean (true if found)
- `By` - Separator
- `Email` - Property to check

**Why use exists instead of find?**
- More efficient (doesn't load entire object)
- Only checks presence, not retrieving data
- Use for validation (e.g., "Is email already registered?")

#### 4. Filtering Collections
```java
List<User> findByRole(Role role);
```

**Generated Query:**
```sql
SELECT * FROM users WHERE role = ?
```

**Return Type Matters:**
- `Optional<User>` - Expects 0 or 1 result
- `List<User>` - Expects 0 or many results
- `User` - Expects exactly 1 result (throws exception if not found)

#### 5. Counting
```java
long countByIsDeletedFalse();
```

**Generated Query:**
```sql
SELECT COUNT(*) FROM users WHERE is_deleted = false
```

**Breakdown:**
- `count` - Operation (returns long)
- `By` - Separator
- `IsDeletedFalse` - Condition

**Use Cases:**
- Dashboard metrics ("Total active users")
- Pagination (total pages)
- Analytics

### Supported Keywords

Spring Data JPA supports many keywords:

| Keyword | Example | SQL |
|---------|---------|-----|
| `And` | `findByNameAndEmail` | `WHERE name = ? AND email = ?` |
| `Or` | `findByNameOrEmail` | `WHERE name = ? OR email = ?` |
| `Is`, `Equals` | `findByName` | `WHERE name = ?` |
| `Not` | `findByNameNot` | `WHERE name <> ?` |
| `IsNull` | `findByEmailIsNull` | `WHERE email IS NULL` |
| `IsNotNull` | `findByEmailIsNotNull` | `WHERE email IS NOT NULL` |
| `Like` | `findByNameLike` | `WHERE name LIKE ?` |
| `StartingWith` | `findByNameStartingWith` | `WHERE name LIKE 'param%'` |
| `EndingWith` | `findByNameEndingWith` | `WHERE name LIKE '%param'` |
| `Containing` | `findByNameContaining` | `WHERE name LIKE '%param%'` |
| `OrderBy` | `findByRoleOrderByNameAsc` | `WHERE role = ? ORDER BY name ASC` |
| `True`/`False` | `findByEmailVerifiedTrue` | `WHERE email_verified = true` |
| `In` | `findByRoleIn(List<Role>)` | `WHERE role IN (?)` |
| `Between` | `findByCreatedAtBetween` | `WHERE created_at BETWEEN ? AND ?` |
| `LessThan` | `findByAgeLessThan` | `WHERE age < ?` |
| `GreaterThan` | `findByAgeGreaterThan` | `WHERE age > ?` |

**Teaching Point:** You rarely need to write SQL. Spring Data JPA naming conventions cover 90% of use cases.

---

## Decision 3: When to Use @Query (Custom Queries)

### Method Naming Limitations

**Some queries are too complex for method names:**

```java
// This would work but is unreadable:
List<User> findByIsDeletedFalseAndNameContainingIgnoreCaseOrEmailContainingIgnoreCase(String name, String email);
```

**Better approach:**
```java
@Query("SELECT u FROM User u WHERE u.isDeleted = false " +
       "AND (LOWER(u.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
       "OR LOWER(u.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
List<User> searchActiveUsers(@Param("searchTerm") String searchTerm);
```

### JPQL vs Native SQL

**JPQL (Java Persistence Query Language):**
```java
@Query("SELECT u FROM User u WHERE u.email = :email")
Optional<User> findByEmail(@Param("email") String email);
```

- Uses entity names (`User`) not table names (`users`)
- Uses field names (`email`) not column names (`email`)
- Database-agnostic (works with PostgreSQL, MySQL, etc.)

**Native SQL:**
```java
@Query(value = "SELECT * FROM users WHERE email = :email", nativeQuery = true)
Optional<User> findByEmail(@Param("email") String email);
```

- Uses actual table and column names
- Database-specific
- Use when you need database-specific features (PostgreSQL full-text search, JSON operators, etc.)

**We chose JPQL because:**
- Database-agnostic (could switch to MySQL if needed)
- Works with JPA entities (automatic mapping)
- Type-safe
- Sufficient for our needs

**When to use Native SQL:**
- Complex joins across many tables
- Database-specific functions (PostgreSQL's `to_tsvector` for full-text search)
- Performance optimization (direct SQL control)
- Legacy database with complex schema

### Breaking Down Our Custom Query

```java
@Query("SELECT u FROM User u WHERE u.isDeleted = false " +
       "AND (LOWER(u.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
       "OR LOWER(u.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
List<User> searchActiveUsers(@Param("searchTerm") String searchTerm);
```

**Line by line:**

1. **`SELECT u FROM User u`**
    - `User` - Entity name (not table name `users`)
    - `u` - Alias for User entity

2. **`WHERE u.isDeleted = false`**
    - Only active users (soft delete filter)

3. **`AND (LOWER(u.name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))`**
    - `LOWER()` - Case-insensitive search
    - `CONCAT('%', :searchTerm, '%')` - Wraps search term with wildcards
    - `LIKE` - Pattern matching
    - Example: Search "john" finds "John Doe", "johnny", "JOHN"

4. **`OR LOWER(u.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')))`**
    - Same pattern for email field
    - Example: Search "gmail" finds "user@gmail.com"

5. **`@Param("searchTerm")`**
    - Binds method parameter to query parameter
    - `:searchTerm` in query → `String searchTerm` in method

**Teaching Point:** Use `@Query` when method names become unreadable or when you need OR conditions, complex joins, or subqueries.

---

## Decision 4: Return Types (Optional vs List vs Entity)

### Why Optional<User> for Single Results?

```java
Optional<User> findByEmail(String email);
```

**What is Optional?**
- Java 8 feature (wrapper around potentially null value)
- Forces caller to handle "not found" case
- Prevents NullPointerException

**Without Optional (Old Way):**
```java
User findByEmail(String email); // Can return null

User user = userRepository.findByEmail("test@example.com");
String name = user.getName(); // NullPointerException if not found!
```

**With Optional (Modern Way):**
```java
Optional<User> findByEmail(String email);

Optional<User> userOpt = userRepository.findByEmail("test@example.com");
if (userOpt.isPresent()) {
    User user = userOpt.get();
    String name = user.getName(); // Safe!
} else {
    // Handle not found case
}

// Or more concisely:
User user = userRepository.findByEmail("test@example.com")
    .orElseThrow(() -> new UserNotFoundException("User not found"));

// Or with default:
User user = userRepository.findByEmail("test@example.com")
    .orElse(defaultUser);
```

**Teaching Point:** Always use `Optional<T>` for single results that might not exist. It makes code safer and more explicit about possible absence.

### Why List<User> for Multiple Results?

```java
List<User> findByRole(Role role);
```

- Returns empty list if no results (never null)
- Safe to iterate without null checks
- Can return many results

**Empty List is Better Than Null:**
```java
List<User> users = userRepository.findByRole(Role.AUTHOR);
// No null check needed!
for (User user : users) {
    // Process user
}
// If no results, loop simply doesn't execute
```

### Return Type Decision Matrix

| Scenario | Return Type | Why |
|----------|-------------|-----|
| Might not exist | `Optional<User>` | Force null handling |
| Must exist | `User` | Throws exception if not found |
| Multiple results | `List<User>` | Never null, safe iteration |
| Check existence | `boolean` | More efficient than loading entity |
| Count | `long` | Direct count without loading data |

---

## Decision 5: Soft Delete Patterns in Queries

### The Soft Delete Challenge

**Problem:**
We use soft delete (`isDeleted` flag), but most operations should ignore deleted users.

**Bad Approach (Forget to Filter):**
```java
// Oops! Includes deleted users
List<User> findByRole(Role role);
```

**Good Approach (Consistent Filtering):**
```java
// Excludes deleted users
List<User> findByRoleAndIsDeletedFalse(Role role);
```

### Our Dual Query Strategy

For important queries, we provide both versions:

**1. Include Deleted (For Admin/Audit):**
```java
Optional<User> findByEmail(String email);
List<User> findByRole(Role role);
```

**2. Exclude Deleted (For Normal Operations):**
```java
Optional<User> findByEmailAndIsDeletedFalse(String email);
List<User> findByRoleAndIsDeletedFalse(Role role);
```

**Why Both?**

**Use Case 1: User Login (Exclude Deleted)**
```java
// User tries to login
Optional<User> user = userRepository.findByEmailAndIsDeletedFalse(email);
if (user.isEmpty()) {
    throw new BadCredentialsException("Invalid credentials");
}
// Deleted users cannot login
```

**Use Case 2: Admin Restore (Include Deleted)**
```java
// Admin wants to restore deleted account
Optional<User> user = userRepository.findByEmail(email);
if (user.isPresent() && user.get().getIsDeleted()) {
    user.get().setIsDeleted(false);
    userRepository.save(user.get());
}
// Can find and restore deleted users
```

**Teaching Point:** Always consider whether deleted entities should be included. Default to excluding them, but provide inclusive versions for admin/audit operations.

---

## Decision 6: Query Method Catalog

### Our 11 Query Methods Explained

#### 1. Email Lookup (With Deleted)
```java
Optional<User> findByEmail(String email);
```
**Use Case:** Admin search, account recovery, finding soft-deleted accounts
**Example:**
```java
Optional<User> user = userRepository.findByEmail("deleted@example.com");
// Finds user even if isDeleted = true
```

#### 2. Email Lookup (Active Only)
```java
Optional<User> findByEmailAndIsDeletedFalse(String email);
```
**Use Case:** Login, profile lookup, normal operations
**Example:**
```java
Optional<User> user = userRepository.findByEmailAndIsDeletedFalse(login.getEmail());
// Only finds if user exists AND is active
```

#### 3. Email Existence Check (With Deleted)
```java
boolean existsByEmail(String email);
```
**Use Case:** Check if email ever registered (prevent email reuse)
**Example:**
```java
if (userRepository.existsByEmail(newUser.getEmail())) {
    throw new EmailAlreadyExistsException("Email is already registered");
}
```

#### 4. Email Existence Check (Active Only)
```java
boolean existsByEmailAndIsDeletedFalse(String email);
```
**Use Case:** Check if active user has this email (for updates)
**Example:**
```java
// When user changes email
if (userRepository.existsByEmailAndIsDeletedFalse(newEmail)) {
    throw new EmailAlreadyInUseException("Email is already in use");
}
```

#### 5. Find By Role (With Deleted)
```java
List<User> findByRole(Role role);
```
**Use Case:** Admin analytics (total authors including deleted)
**Example:**
```java
long totalAuthorsEver = userRepository.findByRole(Role.AUTHOR).size();
```

#### 6. Find By Role (Active Only)
```java
List<User> findByRoleAndIsDeletedFalse(Role role);
```
**Use Case:** Display active authors, role-based filtering
**Example:**
```java
List<User> activeAuthors = userRepository.findByRoleAndIsDeletedFalse(Role.AUTHOR);
// Show on "Our Writers" page
```

#### 7. Find All Active Users
```java
List<User> findByIsDeletedFalse();
```
**Use Case:** User management page, list all users
**Example:**
```java
List<User> users = userRepository.findByIsDeletedFalse();
// Admin dashboard: show all active users
```

#### 8. Count Active Users
```java
long countByIsDeletedFalse();
```
**Use Case:** Dashboard metrics, analytics
**Example:**
```java
long activeUsers = userRepository.countByIsDeletedFalse();
// Display: "Total Users: 1,234"
```

#### 9. Count By Role
```java
long countByRole(Role role);
```
**Use Case:** Role distribution analytics
**Example:**
```java
long subscribers = userRepository.countByRole(Role.SUBSCRIBER);
long authors = userRepository.countByRole(Role.AUTHOR);
long admins = userRepository.countByRole(Role.ADMIN);
// Dashboard: Show role distribution pie chart
```

#### 10. Find Unverified Users
```java
List<User> findByEmailVerifiedFalseAndIsDeletedFalse();
```
**Use Case:** Send verification reminder emails, show incomplete profiles
**Example:**
```java
List<User> unverified = userRepository.findByEmailVerifiedFalseAndIsDeletedFalse();
for (User user : unverified) {
    emailService.sendVerificationReminder(user);
}
```

#### 11. Search Active Users
```java
@Query("...")
List<User> searchActiveUsers(@Param("searchTerm") String searchTerm);
```
**Use Case:** Admin search bar, find users by name or email
**Example:**
```java
List<User> results = userRepository.searchActiveUsers("john");
// Finds: "John Doe", "johnny@example.com", "Johnathan Smith"
```

---

## What Works Now After This Commit?

### Application Capabilities

**We Can Now:**

1. **Basic CRUD Operations** (inherited from JpaRepository):
```java
User user = User.builder()
    .email("test@example.com")
    .password("hashedPassword")
    .name("Test User")
    .role(Role.SUBSCRIBER)
    .build();

userRepository.save(user);              // Create user
Optional<User> found = userRepository.findById(1L);  // Find by ID
userRepository.delete(user);             // Delete user
long count = userRepository.count();     // Count all users
```

2. **Email-Based Queries**:
```java
Optional<User> user = userRepository.findByEmail("test@example.com");
boolean exists = userRepository.existsByEmail("test@example.com");
```

3. **Role-Based Queries**:
```java
List<User> authors = userRepository.findByRoleAndIsDeletedFalse(Role.AUTHOR);
long authorCount = userRepository.countByRole(Role.AUTHOR);
```

4. **Soft Delete Handling**:
```java
List<User> activeUsers = userRepository.findByIsDeletedFalse();
long activeCount = userRepository.countByIsDeletedFalse();
```

5. **Search Functionality**:
```java
List<User> results = userRepository.searchActiveUsers("john");
```

### What Still Doesn't Work

**We Still Cannot:**
- **Call these methods from REST API** (no controllers yet)
- **Authenticate users** (no JWT service, no security configuration)
- **Validate input** (no DTOs, no validation annotations)
- **Hash passwords** (no BCryptPasswordEncoder configured)
- **Return proper API responses** (no response DTOs)

**Next Steps:**
1. Create DTOs (RegisterRequest, LoginRequest, UserResponse)
2. Create JwtService (token generation/validation)
3. Configure Spring Security (SecurityFilterChain, JWT filter)
4. Create UserService (business logic layer)
5. Create AuthController (REST endpoints)

---

## Teaching Talking Points

### For Students New to Spring Data JPA

**Analogy:**
"Think of Spring Data JPA as a very smart assistant:
- You: 'Find user by email'
- Assistant: 'I'll write the SQL: SELECT * FROM users WHERE email = ?'
- You don't write SQL, you just describe what you want in the method name."

**The Magic Explained:**
1. You define an interface with method signatures
2. Spring Data JPA reads method names at startup
3. Generates implementation classes
4. Injects them wherever you use the repository

**It's Not Really Magic:**
- Uses Java Reflection
- Uses Proxy pattern (same pattern as `@Transactional`)
- You can see generated queries in logs (we have `show-sql=true`)

### For Students Coming from Other Frameworks

**Sequelize (Node.js):**
```javascript
// Sequelize
const user = await User.findOne({ where: { email: 'test@example.com' } });

// Spring Data JPA equivalent
Optional<User> user = userRepository.findByEmail("test@example.com");
```

**Django ORM (Python):**
```python
# Django
user = User.objects.get(email='test@example.com')

# Spring Data JPA equivalent
Optional<User> user = userRepository.findByEmail("test@example.com");
```

**Teaching Point:** Every framework has an ORM. The concepts are universal: entities, repositories, queries. Syntax differs, patterns are the same.

### Common Questions

**Q: Why not just write SQL?**
A: You can! But Spring Data JPA provides:
- Type safety (compile-time errors)
- Less boilerplate (no manual mapping)
- Database portability (easier to switch databases)
- Automatic pagination and sorting
- However, for complex queries or performance-critical operations, native SQL is perfectly fine.

**Q: What if my method name is wrong?**
A: Application won't start! Spring Data JPA validates method names at startup. You'll get an error like:
```
Could not create query for public abstract java.util.Optional findByEmeil(java.lang.String);
Reason: No property 'emeil' found for type 'User'
```
This is good - fail fast, not at runtime!

**Q: Can I write my own implementation for a method?**
A: Yes! Create a custom implementation:
```java
// UserRepositoryCustom.java
public interface UserRepositoryCustom {
    List<User> complexCustomQuery();
}

// UserRepositoryCustomImpl.java
@Component
public class UserRepositoryCustomImpl implements UserRepositoryCustom {
    @PersistenceContext
    private EntityManager entityManager;

    public List<User> complexCustomQuery() {
        // Your custom implementation
    }
}

// UserRepository.java
public interface UserRepository extends JpaRepository<User, Long>, UserRepositoryCustom {
    // Spring Data methods
}
```

**Q: Does Spring Data JPA cache results?**
A: No, not by itself. But:
- Hibernate (JPA implementation) has first-level cache (per-transaction)
- You can add second-level cache (Ehcache, Redis) if needed
- Use `@Cacheable` for method-level caching

**Q: What about pagination?**
A: Spring Data JPA supports pagination:
```java
// Change return type to Page<User>
Page<User> findByRoleAndIsDeletedFalse(Role role, Pageable pageable);

// Usage:
Pageable pageable = PageRequest.of(0, 10, Sort.by("name").ascending());
Page<User> page = userRepository.findByRoleAndIsDeletedFalse(Role.AUTHOR, pageable);
```

**Q: How do I see the generated SQL?**
A: We already configured it! Check application.properties:
```properties
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
```
SQL queries will appear in console when methods are called.

---

## Performance Considerations

### Efficient Queries

**Good: Use `exists` for checking**
```java
boolean exists = userRepository.existsByEmail("test@example.com");
// Generates: SELECT COUNT(*) > 0 FROM users WHERE email = ?
// Fast! Only counts, doesn't load data
```

**Bad: Load entity to check existence**
```java
Optional<User> user = userRepository.findByEmail("test@example.com");
boolean exists = user.isPresent();
// Generates: SELECT * FROM users WHERE email = ?
// Slow! Loads all columns unnecessarily
```

### N+1 Query Problem

**Problem:**
```java
List<User> users = userRepository.findAll();
for (User user : users) {
    user.getPosts().size(); // If User has @OneToMany posts
    // This triggers N additional queries!
}
// 1 query to fetch users + N queries to fetch each user's posts = N+1 queries
```

**Solution:**
```java
@Query("SELECT u FROM User u LEFT JOIN FETCH u.posts WHERE u.id = :id")
Optional<User> findByIdWithPosts(@Param("id") Long id);
// Single query with JOIN, loads everything at once
```

**Teaching Point:** Always be aware of lazy loading. Use `JOIN FETCH` for associations you know you'll need. Monitor SQL logs during development to catch N+1 problems early.

### Indexing

Remember, we created indexes in Flyway migration:
```sql
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_role ON users(role);
CREATE INDEX idx_users_is_deleted ON users(is_deleted);
```

These make our repository methods fast:
- `findByEmail()` - Uses `idx_users_email` index
- `findByRole()` - Uses `idx_users_role` index
- `findByIsDeletedFalse()` - Uses `idx_users_is_deleted` index

**Teaching Point:** Repository methods are only fast if database is properly indexed. Work with database team (or your DBA skills) to ensure critical columns are indexed.

---

## Testing the Repository

### Integration Test Example

```java
@SpringBootTest
@Transactional // Rollback after each test
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void testFindByEmail() {
        // Given
        User user = User.builder()
            .email("test@example.com")
            .password("password")
            .name("Test User")
            .role(Role.SUBSCRIBER)
            .build();
        userRepository.save(user);

        // When
        Optional<User> found = userRepository.findByEmail("test@example.com");

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Test User");
    }

    @Test
    void testSoftDeleteFiltering() {
        // Given
        User activeUser = createUser("active@example.com", false);
        User deletedUser = createUser("deleted@example.com", true);

        // When
        List<User> activeUsers = userRepository.findByIsDeletedFalse();

        // Then
        assertThat(activeUsers).hasSize(1);
        assertThat(activeUsers.get(0).getEmail()).isEqualTo("active@example.com");
    }

    private User createUser(String email, boolean isDeleted) {
        return userRepository.save(User.builder()
            .email(email)
            .password("password")
            .name("Test")
            .role(Role.SUBSCRIBER)
            .isDeleted(isDeleted)
            .build());
    }
}
```

**Teaching Point:** Repository tests are integration tests (require database). Use `@SpringBootTest` and `@Transactional` for automatic rollback.

---

## Architectural Decisions Record

**ADR-007: Use Spring Data JPA for Data Access**
- **Status:** Accepted
- **Context:** Need data access layer for User entity
- **Decision:** Spring Data JPA with derived queries and custom @Query methods
- **Consequences:**
    - Less boilerplate (no DAO classes)
    - Must understand naming conventions
    - Limited to JPA capabilities (complex queries might need native SQL)

**ADR-008: Provide Dual Queries for Soft Delete**
- **Status:** Accepted
- **Context:** Soft delete pattern requires filtering deleted entities
- **Decision:** Provide both inclusive and exclusive methods (e.g., `findByEmail` and `findByEmailAndIsDeletedFalse`)
- **Consequences:**
    - More methods in repository
    - Clearer intent (explicit about deleted entities)
    - Prevents accidental inclusion of deleted data

**ADR-009: Use Optional for Single Results**
- **Status:** Accepted
- **Context:** Single-result queries might not find entity
- **Decision:** Return `Optional<User>` instead of `User` or `null`
- **Consequences:**
    - Safer code (forces null handling)
    - More verbose (must unwrap Optional)
    - Modern Java best practice

---

## Commit Stats
- **Files created:** 1
    - UserRepository.java (118 lines including docs)
- **Methods defined:** 11 custom queries
- **Inherited methods:** 20+ from JpaRepository
- **Lines of code:** ~50 (excluding comments/docs)
- **Documentation:** ~70 lines of JavaDoc

---

## Resources for Students
- Spring Data JPA Reference: https://docs.spring.io/spring-data/jpa/docs/current/reference/html/
- Query Methods: https://docs.spring.io/spring-data/jpa/docs/current/reference/html/#jpa.query-methods
- @Query Annotation: https://www.baeldung.com/spring-data-jpa-query
- Optional in Java: https://www.baeldung.com/java-optional
- Repository Testing: https://www.baeldung.com/spring-boot-testing
- JPQL: https://www.baeldung.com/jpql-hibernate