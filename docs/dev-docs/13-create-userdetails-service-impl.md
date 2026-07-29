# Commit 13: Create UserDetailsService Implementation

**Date:** February 1, 2026
**Commit Message:** Create UserDetailsService Implementation

---

## Development Mindset & Decision Process

### The Spring Security Integration Layer

**Current State:**
- JwtAuthenticationFilter ready to load user details
- User entity and repository available
- Need standard Spring Security integration

**Goal:**
- Create UserDetailsServiceImpl implementing Spring Security's UserDetailsService
- Build complete UserDetails with account status flags
- Provide efficient user loading by ID for JWT authentication

**Teaching Point:** UserDetailsService is Spring Security's core interface for loading user data. By implementing it, we bridge our User entity with Spring Security's authentication system.

---

## Decision 1: UserDetailsService Interface

### What Spring Security Expects

```java
public interface UserDetailsService {
    UserDetails loadUserByUsername(String username) throws UsernameNotFoundException;
}
```

**Single method contract:**
- Input: username (in our case, email)
- Output: UserDetails object
- Exception: UsernameNotFoundException if not found

### Our Implementation

```java
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmailAndIsDeletedFalse(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        return buildUserDetails(user);
    }
}
```

**Why direct repository access (not UserService)?**
- UserDetailsService is infrastructure, not business logic
- Avoids circular dependency issues
- More explicit about what's happening
- Single responsibility - just load user data

---

## Decision 2: UserDetails Builder Pattern

### Complete UserDetails Construction

```java
return org.springframework.security.core.userdetails.User.builder()
        .username(user.getEmail())
        .password(user.getPassword())
        .authorities("ROLE_" + user.getRole().name())
        .accountExpired(false)
        .accountLocked(false)
        .credentialsExpired(false)
        .disabled(user.getIsDeleted())
        .build();
```

### Field Mappings

| UserDetails Field | Our Value | Purpose |
|-------------------|-----------|---------|
| `username` | `user.getEmail()` | Principal identifier |
| `password` | `user.getPassword()` | BCrypt hash for verification |
| `authorities` | `ROLE_` + role name | Authorization decisions |
| `accountExpired` | `false` | Account expiration (not implemented) |
| `accountLocked` | `false` | Account lockout (not implemented) |
| `credentialsExpired` | `false` | Password expiration (not implemented) |
| `disabled` | `user.getIsDeleted()` | Soft-deleted users are disabled |

### Account Status Flags Explained

```java
.accountExpired(false)      // Future: subscription expiration
.accountLocked(false)       // Future: failed login lockout
.credentialsExpired(false)  // Future: force password change
.disabled(user.getIsDeleted())  // NOW: soft-deleted users blocked
```

**Spring Security checks these during authentication:**
```java
// AbstractUserDetailsAuthenticationProvider.java (simplified)
if (!user.isAccountNonLocked()) {
    throw new LockedException("Account is locked");
}
if (!user.isEnabled()) {
    throw new DisabledException("Account is disabled");
}
if (!user.isAccountNonExpired()) {
    throw new AccountExpiredException("Account has expired");
}
if (!user.isCredentialsNonExpired()) {
    throw new CredentialsExpiredException("Credentials have expired");
}
```

**Teaching Point:** These flags are inverted in the builder (e.g., `accountExpired(false)` means "NOT expired"). The UserDetails interface uses positive phrasing (`isAccountNonExpired()`).

---

## Decision 3: Loading User by ID

### Why We Need This

**JWT contains user ID, not email:**
```json
{
  "sub": "123",      // User ID
  "email": "...",    // For display
  "role": "..."      // For quick checks
}
```

**Standard interface only has loadByUsername:**
```java
// Can't use this for JWT!
UserDetails loadUserByUsername(String username);
```

### Our Extension

```java
public UserDetails loadUserById(Long userId) throws UsernameNotFoundException {
    User user = userRepository.findById(userId)
            .filter(u -> !u.getIsDeleted())
            .orElseThrow(() -> new UsernameNotFoundException("User not found with id: " + userId));

    return org.springframework.security.core.userdetails.User.builder()
            .username(user.getEmail())
            .password(user.getPassword())
            .authorities("ROLE_" + user.getRole().name())
            .accountExpired(false)
            .accountLocked(false)
            .credentialsExpired(false)
            .disabled(user.getIsDeleted())
            .build();
}
```

**Why filter instead of custom query?**
```java
// Option 1: Filter in Java (our choice)
userRepository.findById(userId)
        .filter(u -> !u.getIsDeleted())

// Option 2: Custom query method
userRepository.findByIdAndIsDeletedFalse(userId)
```

For single record by primary key, the difference is negligible. Filter keeps repository simpler.

---

## Decision 4: Role to Authority Mapping

### Spring Security Conventions

```java
.authorities("ROLE_" + user.getRole().name())
```

**Transforms:**
- `Role.SUBSCRIBER` → `"ROLE_SUBSCRIBER"`
- `Role.AUTHOR` → `"ROLE_AUTHOR"`
- `Role.ADMIN` → `"ROLE_ADMIN"`

### Why the "ROLE_" Prefix?

**Spring Security has two concepts:**
1. **Authorities** - Generic permissions (e.g., `READ_POSTS`, `DELETE_USERS`)
2. **Roles** - Special authorities with `ROLE_` prefix

**These are equivalent:**
```java
@PreAuthorize("hasRole('ADMIN')")          // Checks for ROLE_ADMIN
@PreAuthorize("hasAuthority('ROLE_ADMIN')") // Same check
```

**Convention:**
- Use `hasRole()` for role-based checks (adds ROLE_ automatically)
- Use `hasAuthority()` for fine-grained permissions

---

## What Works Now After This Commit?

### Spring Security Integration Complete

**For JWT Authentication:**
```java
// JwtAuthenticationFilter uses this
UserDetails userDetails = userDetailsService.loadUserById(userId);
// Returns complete UserDetails with authorities
```

**For Form Login (if ever needed):**
```java
// Spring Security would call this automatically
UserDetails userDetails = userDetailsService.loadUserByUsername(email);
```

### UserDetails Contains

```java
UserDetails {
    username: "user@example.com"
    password: "$2a$12$..." (BCrypt hash)
    authorities: [ROLE_SUBSCRIBER]
    accountNonExpired: true
    accountNonLocked: true
    credentialsNonExpired: true
    enabled: true (or false if soft-deleted)
}
```

### Integration with JwtAuthenticationFilter

```java
// In JwtAuthenticationFilter
private final UserDetailsServiceImpl userDetailsService;

// When processing request
Long userId = jwtUtil.getUserIdFromToken(jwt);
UserDetails userDetails = userDetailsService.loadUserById(userId);

// Create authentication
UsernamePasswordAuthenticationToken authentication =
    new UsernamePasswordAuthenticationToken(
        userDetails,
        null,
        userDetails.getAuthorities()  // [ROLE_SUBSCRIBER]
    );
```

---

## Future Enhancements

### Account Lockout (Future)

```java
// Add to User entity
private Integer failedLoginAttempts = 0;
private LocalDateTime lockoutUntil;

// In UserDetailsServiceImpl
.accountLocked(user.getLockoutUntil() != null
    && user.getLockoutUntil().isAfter(LocalDateTime.now()))
```

### Password Expiration (Future)

```java
// Add to User entity
private LocalDateTime passwordChangedAt;

// In UserDetailsServiceImpl
.credentialsExpired(user.getPasswordChangedAt()
    .plusDays(90)
    .isBefore(LocalDateTime.now()))
```

### Subscription Expiration (Future)

```java
// Add to User entity
private LocalDateTime subscriptionExpiresAt;

// In UserDetailsServiceImpl
.accountExpired(user.getRole() == Role.AUTHOR
    && user.getSubscriptionExpiresAt().isBefore(LocalDateTime.now()))
```

---

## Teaching Talking Points

### For Students New to UserDetailsService

**Analogy - ID Card System:**
```
Employee Database (User Entity)
    - employee_id: 123
    - email: john@company.com
    - password_hash: $2a$...
    - department: Engineering
    - active: true

ID Card Reader (UserDetailsService)
    - Looks up employee by badge number
    - Creates access card (UserDetails) with:
      - Name badge: john@company.com
      - Access level: ROLE_ENGINEER
      - Valid: yes (active = true)
      - Expired: no

Security System (Spring Security)
    - Reads the access card
    - Checks if card is valid
    - Grants/denies access based on level
```

### Common Implementation Mistakes

**1. Using Wrong Exception:**
```java
// DON'T
throw new RuntimeException("User not found");

// DO
throw new UsernameNotFoundException("User not found");
```
Spring Security expects UsernameNotFoundException specifically.

**2. Forgetting Soft Delete Check:**
```java
// DON'T - Returns deleted users!
userRepository.findByEmail(email)

// DO
userRepository.findByEmailAndIsDeletedFalse(email)
```

**3. Hardcoding Roles:**
```java
// DON'T
.authorities("ROLE_USER")

// DO
.authorities("ROLE_" + user.getRole().name())
```

**4. Storing Plain Password:**
```java
// DON'T
.password(request.getPassword())  // Plain text!

// DO
.password(user.getPassword())  // BCrypt hash from DB
```

---

## Architectural Decisions Record

**ADR-037: Direct Repository Access in UserDetailsService**
- **Status:** Accepted
- **Context:** Need to load user data for Spring Security
- **Decision:** Inject UserRepository directly, not UserService
- **Consequences:**
    - Avoids potential circular dependencies
    - Clearer separation (infrastructure vs business)
    - Slightly more coupling to repository

**ADR-038: Custom loadUserById Method**
- **Status:** Accepted
- **Context:** JWT contains user ID, not email
- **Decision:** Add non-interface method `loadUserById(Long)`
- **Consequences:**
    - More efficient than loading by email
    - Requires concrete type reference (not interface)
    - Matches JWT payload structure

**ADR-039: Soft Delete Maps to Disabled**
- **Status:** Accepted
- **Context:** How to handle soft-deleted users in Spring Security
- **Decision:** Map `isDeleted` to `disabled` flag in UserDetails
- **Consequences:**
    - Soft-deleted users can't authenticate
    - Clear error message ("Account is disabled")
    - Data preserved in database

---

## Commit Stats
- **Files created:** 1
    - UserDetailsServiceImpl.java (64 lines)
- **Files modified:** 1
    - JwtAuthenticationFilter.java (updated dependency)
- **Files removed:** 1
    - CustomUserDetailsService.java (replaced by this implementation)
- **Methods implemented:** 2
    - `loadUserByUsername(String email)` - Interface method
    - `loadUserById(Long userId)` - Custom method for JWT

---

## Testing the UserDetailsService

### Unit Test Example

```java
@ExtendWith(MockitoExtension.class)
class UserDetailsServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserDetailsServiceImpl userDetailsService;

    @Test
    void loadUserByUsername_shouldReturnUserDetails_whenUserExists() {
        User user = User.builder()
                .id(1L)
                .email("test@example.com")
                .password("hashedPassword")
                .role(Role.AUTHOR)
                .isDeleted(false)
                .build();

        when(userRepository.findByEmailAndIsDeletedFalse("test@example.com"))
                .thenReturn(Optional.of(user));

        UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

        assertThat(userDetails.getUsername()).isEqualTo("test@example.com");
        assertThat(userDetails.getPassword()).isEqualTo("hashedPassword");
        assertThat(userDetails.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_AUTHOR");
        assertThat(userDetails.isEnabled()).isTrue();
        assertThat(userDetails.isAccountNonExpired()).isTrue();
        assertThat(userDetails.isAccountNonLocked()).isTrue();
        assertThat(userDetails.isCredentialsNonExpired()).isTrue();
    }

    @Test
    void loadUserByUsername_shouldThrow_whenUserNotFound() {
        when(userRepository.findByEmailAndIsDeletedFalse("notfound@example.com"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                userDetailsService.loadUserByUsername("notfound@example.com"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void loadUserById_shouldReturnUserDetails_whenUserExists() {
        User user = User.builder()
                .id(1L)
                .email("test@example.com")
                .password("hashedPassword")
                .role(Role.ADMIN)
                .isDeleted(false)
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        UserDetails userDetails = userDetailsService.loadUserById(1L);

        assertThat(userDetails.getUsername()).isEqualTo("test@example.com");
        assertThat(userDetails.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void loadUserById_shouldThrow_whenUserDeleted() {
        User user = User.builder()
                .id(1L)
                .email("test@example.com")
                .isDeleted(true)  // Soft deleted!
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userDetailsService.loadUserById(1L))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void loadUserByUsername_shouldSetDisabled_whenUserDeleted() {
        // This tests the edge case where findByEmailAndIsDeletedFalse
        // somehow returns a deleted user (shouldn't happen, but defensive)
        User user = User.builder()
                .id(1L)
                .email("test@example.com")
                .password("hashedPassword")
                .role(Role.SUBSCRIBER)
                .isDeleted(true)
                .build();

        when(userRepository.findByEmailAndIsDeletedFalse("test@example.com"))
                .thenReturn(Optional.of(user));

        UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

        // disabled flag should reflect isDeleted
        assertThat(userDetails.isEnabled()).isFalse();
    }

    @Test
    void shouldMapAllRolesCorrectly() {
        for (Role role : Role.values()) {
            User user = User.builder()
                    .id(1L)
                    .email("test@example.com")
                    .password("hashedPassword")
                    .role(role)
                    .isDeleted(false)
                    .build();

            when(userRepository.findByEmailAndIsDeletedFalse("test@example.com"))
                    .thenReturn(Optional.of(user));

            UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

            assertThat(userDetails.getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_" + role.name());
        }
    }
}
```

### Integration Test

```java
@SpringBootTest
@Transactional
class UserDetailsServiceIntegrationTest {

    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void shouldLoadUserFromDatabase() {
        // Create test user
        User user = User.builder()
                .email("integration@example.com")
                .password(passwordEncoder.encode("password123"))
                .name("Integration Test")
                .role(Role.AUTHOR)
                .emailVerified(false)
                .isDeleted(false)
                .build();
        userRepository.save(user);

        // Load by username (email)
        UserDetails byEmail = userDetailsService.loadUserByUsername("integration@example.com");
        assertThat(byEmail.getUsername()).isEqualTo("integration@example.com");
        assertThat(byEmail.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_AUTHOR");

        // Load by ID
        UserDetails byId = userDetailsService.loadUserById(user.getId());
        assertThat(byId.getUsername()).isEqualTo("integration@example.com");
    }
}
```

---

## Resources for Students
- UserDetailsService: https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/user-details-service.html
- UserDetails: https://docs.spring.io/spring-security/site/docs/current/api/org/springframework/security/core/userdetails/UserDetails.html
- GrantedAuthority: https://docs.spring.io/spring-security/reference/servlet/authorization/architecture.html
- Spring Security Authentication: https://docs.spring.io/spring-security/reference/servlet/authentication/architecture.html
