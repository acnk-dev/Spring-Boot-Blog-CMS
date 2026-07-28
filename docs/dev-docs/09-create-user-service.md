# Commit 9: Create UserService Interface and Implementation

**Date:** February 1, 2026
**Commit Message:** Create UserService Interface and Implementation

---

## Development Mindset & Decision Process

### The Service Layer

**Current State:**
- User entity and repository ready
- Authentication DTOs created
- JWT utility ready
- But no business logic layer to orchestrate operations!

**Goal:**
- Create UserService interface (contract)
- Create UserServiceImpl (implementation)
- Establish service layer patterns for the project
- Implement CRUD operations with soft-delete support

**Teaching Point:** The Service Layer sits between Controllers and Repositories. It contains business logic, orchestrates multiple repository calls, handles transactions, and provides a clean API for controllers. This separation of concerns makes code testable, maintainable, and reusable.

---

## Decision 1: Why Interface + Implementation?

### The Pattern

```
Controller → Service (Interface) → ServiceImpl → Repository
```

**Why Not Just Use a Class?**

```java
// Without interface
@RestController
public class UserController {
    private final UserServiceImpl userService;  // Coupled to implementation
}

// With interface
@RestController
public class UserController {
    private final UserService userService;  // Coupled to contract only
}
```

### Benefits of Interface + Implementation

| Benefit | Explanation |
|---------|-------------|
| **Loose Coupling** | Controller depends on contract, not implementation |
| **Testability** | Easy to mock in unit tests |
| **Flexibility** | Can swap implementations (e.g., CachingUserServiceImpl) |
| **Documentation** | Interface acts as API documentation |
| **Spring AOP** | Proxies work better with interfaces |

### When to Skip Interface?

For small projects or simple services, a single class might suffice. But for:
- Services with complex business logic
- Services you'll want to mock in tests
- Services that might have multiple implementations

...use the interface pattern.

**Teaching Point:** This is a classic example of "Program to an interface, not an implementation" - one of the most important OOP principles.

---

## Decision 2: Package Structure

### Our Structure

```
service/
├── UserService.java           (interface)
└── impl/
    └── UserServiceImpl.java   (implementation)
```

### Alternatives

**Flat Structure:**
```
service/
├── UserService.java
└── UserServiceImpl.java
```
Simple, but gets messy with many services.

**Feature-Based:**
```
user/
├── UserService.java
├── UserServiceImpl.java
├── UserRepository.java
└── UserController.java
```
Good for microservices, but mixes layers.

**Our Choice Rationale:**
- `service/` package for interfaces (the contracts)
- `service/impl/` for implementations
- Clear separation of what vs how
- Easy to find all service contracts in one place

---

## Decision 3: Method Design

### Methods Implemented

```java
public interface UserService {
    User createUser(User user);
    Optional<User> findByEmail(String email);
    Optional<User> findById(Long id);
    User updateUser(Long id, User user);
    void deleteUser(Long id);
    boolean existsByEmail(String email);
}
```

### Method-by-Method Analysis

#### createUser(User user)

```java
@Override
@Transactional
public User createUser(User user) {
    return userRepository.save(user);
}
```

**Simple pass-through** - but the service layer allows us to:
- Add validation logic later
- Trigger events (e.g., send welcome email)
- Add logging/metrics
- Coordinate with other services

#### findByEmail(String email)

```java
@Override
public Optional<User> findByEmail(String email) {
    return userRepository.findByEmailAndIsDeletedFalse(email);
}
```

**Returns only active users** - soft-deleted users are filtered out. This is the safe default for most use cases.

#### findById(Long id)

```java
@Override
public Optional<User> findById(Long id) {
    return userRepository.findById(id)
            .filter(user -> !user.getIsDeleted());
}
```

**Why filter instead of custom query?**
- `findById` is optimized by JPA (uses entity cache)
- Filter in Java is fast (single boolean check)
- Keeps repository interface cleaner

#### updateUser(Long id, User user)

```java
@Override
@Transactional
public User updateUser(Long id, User updatedUser) {
    User existingUser = userRepository.findById(id)
            .filter(user -> !user.getIsDeleted())
            .orElseThrow(() -> new RuntimeException("User not found with id: " + id));

    if (updatedUser.getName() != null) {
        existingUser.setName(updatedUser.getName());
    }
    if (updatedUser.getBio() != null) {
        existingUser.setBio(updatedUser.getBio());
    }
    if (updatedUser.getAvatarUrl() != null) {
        existingUser.setAvatarUrl(updatedUser.getAvatarUrl());
    }

    return userRepository.save(existingUser);
}
```

**Partial update pattern:**
- Only updates non-null fields
- Prevents accidental data loss
- Email, password, role are NOT updated here (separate methods for those)

**Why not update email/password/role?**
- **Email:** Requires verification flow
- **Password:** Requires old password confirmation
- **Role:** Requires admin authorization

#### deleteUser(Long id)

```java
@Override
@Transactional
public void deleteUser(Long id) {
    User user = userRepository.findById(id)
            .filter(u -> !u.getIsDeleted())
            .orElseThrow(() -> new RuntimeException("User not found with id: " + id));

    user.setIsDeleted(true);
    userRepository.save(user);
}
```

**Soft delete implementation:**
- Sets `isDeleted = true`
- Doesn't actually remove from database
- User data preserved for audit/recovery
- All queries filter by `isDeleted = false`

#### existsByEmail(String email)

```java
@Override
public boolean existsByEmail(String email) {
    return userRepository.existsByEmailAndIsDeletedFalse(email);
}
```

**Used for registration validation:**
- Check if email is available before creating account
- Counts only active users (allows re-registration after soft delete)

---

## Decision 4: Transaction Management

### Class-Level Annotation

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {
```

**`@Transactional(readOnly = true)` at class level:**
- All methods are read-only transactions by default
- Optimizes database connections
- Prevents accidental writes in query methods

### Method-Level Override

```java
@Override
@Transactional  // Overrides class-level readOnly=true
public User createUser(User user) {
    return userRepository.save(user);
}
```

**`@Transactional` (without readOnly) for write operations:**
- `createUser` - writes new data
- `updateUser` - modifies existing data
- `deleteUser` - modifies isDeleted flag

### Why This Pattern?

```java
// Query methods (majority) - read-only by default
findByEmail()     // Uses class-level @Transactional(readOnly = true)
findById()        // Uses class-level @Transactional(readOnly = true)
existsByEmail()   // Uses class-level @Transactional(readOnly = true)

// Write methods (minority) - explicit @Transactional
createUser()      // Overrides with @Transactional
updateUser()      // Overrides with @Transactional
deleteUser()      // Overrides with @Transactional
```

**Teaching Point:** Most service methods are reads. Setting `readOnly = true` as default and overriding for writes is safer and more efficient than the reverse.

---

## Decision 5: Dependency Injection

### Constructor Injection with Lombok

```java
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    // Lombok generates constructor
}
```

**Equivalent to:**
```java
@Service
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }
}
```

### Why Constructor Injection?

| Approach | Pros | Cons |
|----------|------|------|
| Field Injection (`@Autowired`) | Less code | Can't make fields final, harder to test |
| Setter Injection | Optional dependencies | Mutable, can be called multiple times |
| **Constructor Injection** | Immutable, required deps clear, testable | More code (Lombok solves this) |

**Teaching Point:** Constructor injection makes dependencies explicit and allows for easy unit testing without Spring context.

---

## Decision 6: Exception Handling (Temporary)

### Current Approach

```java
.orElseThrow(() -> new RuntimeException("User not found with id: " + id));
```

**This is temporary!** We're using `RuntimeException` for now.

### Future Improvement

We'll create custom exceptions:
```java
// Coming in future commit
public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(Long id) {
        super("User not found with id: " + id);
    }
}

public class EmailAlreadyExistsException extends RuntimeException {
    public EmailAlreadyExistsException(String email) {
        super("Email already registered: " + email);
    }
}
```

And a GlobalExceptionHandler:
```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(404, ex.getMessage()));
    }
}
```

---

## What Works Now After This Commit?

### Service Layer Ready

**Can be used in AuthService (next commit):**
```java
@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthResponse register(RegisterRequest request) {
        // Check if email exists
        if (userService.existsByEmail(request.getEmail())) {
            throw new EmailAlreadyExistsException(request.getEmail());
        }

        // Create user
        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .name(request.getName())
                .role(Role.SUBSCRIBER)
                .build();

        user = userService.createUser(user);

        // Generate tokens and return response
        // ...
    }

    public AuthResponse login(LoginRequest request) {
        User user = userService.findByEmail(request.getEmail())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        // Generate tokens and return response
        // ...
    }
}
```

**Can be used in UserController (future):**
```java
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUser(@PathVariable Long id) {
        return userService.findById(id)
                .map(UserResponse::fromEntity)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> updateUser(
            @PathVariable Long id,
            @RequestBody UpdateUserRequest request) {
        // Convert DTO to entity, update, convert back
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
}
```

### What Still Doesn't Work

- No AuthService (authentication business logic)
- No AuthController (REST endpoints)
- No custom exceptions (using RuntimeException)
- No GlobalExceptionHandler (error responses)

---

## Teaching Talking Points

### For Students New to Service Layer

**Analogy - Restaurant Kitchen:**
```
Customer (Client)
    ↓
Waiter (Controller)
    - Takes order (request)
    - Delivers food (response)
    - Doesn't cook!
    ↓
Chef (Service)
    - Knows recipes (business logic)
    - Coordinates ingredients
    - Manages cooking process
    ↓
Pantry (Repository)
    - Stores ingredients (data)
    - Retrieves on request
```

### Common Mistakes to Avoid

**1. Business Logic in Controller:**
```java
// DON'T
@PostMapping("/users")
public ResponseEntity<?> createUser(@RequestBody UserRequest req) {
    if (userRepository.existsByEmail(req.getEmail())) {
        return ResponseEntity.badRequest().body("Email exists");
    }
    User user = new User();
    user.setEmail(req.getEmail());
    user.setPassword(passwordEncoder.encode(req.getPassword()));
    // ... more logic
    userRepository.save(user);
}

// DO
@PostMapping("/users")
public ResponseEntity<UserResponse> createUser(@RequestBody UserRequest req) {
    User user = userService.createUser(req);
    return ResponseEntity.ok(UserResponse.fromEntity(user));
}
```

**2. Repository in Controller:**
```java
// DON'T
@RestController
public class UserController {
    private final UserRepository userRepository;  // Direct repo access
}

// DO
@RestController
public class UserController {
    private final UserService userService;  // Through service
}
```

**3. Forgetting @Transactional on Writes:**
```java
// DON'T - no transaction
public void deleteUser(Long id) {
    User user = userRepository.findById(id).orElseThrow();
    user.setIsDeleted(true);
    userRepository.save(user);  // Might fail partially
}

// DO - atomic operation
@Transactional
public void deleteUser(Long id) {
    // All or nothing
}
```

---

## Architectural Decisions Record

**ADR-022: Interface + Implementation for Services**
- **Status:** Accepted
- **Context:** Need service layer with proper abstraction
- **Decision:** Create interface in `service/` and implementation in `service/impl/`
- **Consequences:**
    - Better testability (easy mocking)
    - Loose coupling
    - Additional files to maintain

**ADR-023: Soft Delete in Service Layer**
- **Status:** Accepted
- **Context:** deleteUser should not hard-delete data
- **Decision:** Set `isDeleted = true` instead of `DELETE` SQL
- **Consequences:**
    - Data preserved for audit
    - Queries must filter deleted records
    - Potential data growth (soft-deleted never removed)

**ADR-024: Class-Level readOnly Transactions**
- **Status:** Accepted
- **Context:** Most methods are queries
- **Decision:** `@Transactional(readOnly = true)` at class level, override for writes
- **Consequences:**
    - Better performance for reads
    - Must remember to add `@Transactional` for writes
    - Clear distinction between read/write operations

**ADR-025: Partial Update Pattern**
- **Status:** Accepted
- **Context:** updateUser should only modify provided fields
- **Decision:** Check for null before setting each field
- **Consequences:**
    - Prevents accidental data loss
    - Slightly more code
    - Clear intent on what can be updated

---

## Commit Stats
- **Files created:** 2
    - UserService.java (interface, 53 lines)
    - UserServiceImpl.java (implementation, 68 lines)
- **Packages created:** 2
    - service
    - service.impl
- **Methods implemented:** 6
    - createUser, findByEmail, findById
    - updateUser, deleteUser, existsByEmail
- **Annotations used:**
    - @Service, @RequiredArgsConstructor
    - @Transactional, @Transactional(readOnly = true)
    - @Override

---

## Testing the UserService

### Unit Test Example

```java
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void createUser_shouldSaveAndReturnUser() {
        User user = User.builder()
                .email("test@example.com")
                .name("Test User")
                .build();

        User savedUser = User.builder()
                .id(1L)
                .email("test@example.com")
                .name("Test User")
                .build();

        when(userRepository.save(user)).thenReturn(savedUser);

        User result = userService.createUser(user);

        assertThat(result.getId()).isEqualTo(1L);
        verify(userRepository).save(user);
    }

    @Test
    void findByEmail_shouldReturnUser_whenExists() {
        User user = User.builder()
                .id(1L)
                .email("test@example.com")
                .isDeleted(false)
                .build();

        when(userRepository.findByEmailAndIsDeletedFalse("test@example.com"))
                .thenReturn(Optional.of(user));

        Optional<User> result = userService.findByEmail("test@example.com");

        assertThat(result).isPresent();
        assertThat(result.get().getEmail()).isEqualTo("test@example.com");
    }

    @Test
    void findByEmail_shouldReturnEmpty_whenNotExists() {
        when(userRepository.findByEmailAndIsDeletedFalse("notfound@example.com"))
                .thenReturn(Optional.empty());

        Optional<User> result = userService.findByEmail("notfound@example.com");

        assertThat(result).isEmpty();
    }

    @Test
    void deleteUser_shouldSetIsDeletedTrue() {
        User user = User.builder()
                .id(1L)
                .isDeleted(false)
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);

        userService.deleteUser(1L);

        assertThat(user.getIsDeleted()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    void deleteUser_shouldThrow_whenUserNotFound() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteUser(999L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void updateUser_shouldUpdateOnlyProvidedFields() {
        User existingUser = User.builder()
                .id(1L)
                .name("Old Name")
                .bio("Old Bio")
                .avatarUrl("old-url")
                .isDeleted(false)
                .build();

        User updateData = User.builder()
                .name("New Name")
                .bio(null)  // Should not update
                .avatarUrl(null)  // Should not update
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        User result = userService.updateUser(1L, updateData);

        assertThat(result.getName()).isEqualTo("New Name");
        assertThat(result.getBio()).isEqualTo("Old Bio");  // Unchanged
        assertThat(result.getAvatarUrl()).isEqualTo("old-url");  // Unchanged
    }
}
```

### Integration Test Example

```java
@SpringBootTest
@Transactional
class UserServiceIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void fullUserLifecycle() {
        // Create
        User user = User.builder()
                .email("integration@example.com")
                .password("hashedPassword")
                .name("Integration Test")
                .role(Role.SUBSCRIBER)
                .build();

        User created = userService.createUser(user);
        assertThat(created.getId()).isNotNull();

        // Find
        Optional<User> found = userService.findById(created.getId());
        assertThat(found).isPresent();

        // Update
        User updateData = User.builder().name("Updated Name").build();
        User updated = userService.updateUser(created.getId(), updateData);
        assertThat(updated.getName()).isEqualTo("Updated Name");

        // Delete (soft)
        userService.deleteUser(created.getId());

        // Verify soft-deleted
        Optional<User> afterDelete = userService.findById(created.getId());
        assertThat(afterDelete).isEmpty();  // Filtered out

        // But still in database
        Optional<User> inDb = userRepository.findById(created.getId());
        assertThat(inDb).isPresent();
        assertThat(inDb.get().getIsDeleted()).isTrue();
    }
}
```

---

## Resources for Students
- Spring Service Layer: https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/stereotype-annotations.html
- @Transactional Deep Dive: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative.html
- Interface Segregation Principle: https://en.wikipedia.org/wiki/Interface_segregation_principle
- Dependency Injection: https://martinfowler.com/articles/injection.html
- Mockito: https://site.mockito.org/
