# Package Explanations
## 🔐 JWT Dependencies (3 packages)
### 1. jjwt-api
- What: Interface/API for JWT operations
- Why: Defines contracts for creating/parsing tokens
- Contains: Jwts.builder(), Claims, JwtParser

### 2. jjwt-impl

- What: Implementation of jjwt-api
- Why: Actual code that creates/validates JWTs
- Scope: runtime (not needed at compile time)

### 3. jjwt-jackson

- What: JSON processor for JWT
- Why: Converts JWT payload to/from JSON
- Alternative: Could use Gson, but Jackson is Spring's default

Note: Why 3 separate packages?
Modular design (can swap implementations)
Industry standard (like SLF4J pattern)


🗄️ Flyway
flyway-core

What: Database migration tool
Why: Version control for database schema
Does: Runs SQL migrations automatically on startup
Tracks: Which migrations ran (in flyway_schema_history table)

Without Flyway:
```
Manual SQL scripts
No version tracking
Team sync issues
Hard to rollback
```

With Flyway:
```
V1__create_users.sql runs automatically
Version tracked
Team synced via Git
Easy rollback
```

🛠️ Lombok
lombok

What: Annotation processor
Why: Reduces boilerplate code
Generates: Getters, setters, constructors, toString, equals, hashCode

Without Lombok:
```java
public class User {
private String name;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    // ... 20+ lines for 5 fields
}
```

With Lombok:
```java
@Getter
@Setter
public class User {
private String name;
// Done! Getters/setters auto-generated
}
```
Common Annotations:
```
@Getter/@Setter - Generate getters/setters
@NoArgsConstructor - Generate empty constructor
@AllArgsConstructor - Generate constructor with all fields
@Data - All of above + toString + equals + hashCode
@Builder - Builder pattern
```
