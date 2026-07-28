# Lombok `@Builder` Pattern — Complete Explanation

The `@Builder` annotation in Lombok implements the **Builder Design Pattern**, allowing you to construct objects in a readable, flexible, and safe way—especially useful for classes with many fields.

---

## 🎯 How `@Builder` Works

---

## 📚 Part 1: What `@Builder` Generates

### Our `User` Entity (Source Code)

```java
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    private Long id;
    private String email;
    private String password;
    private String name;
    private Role role;
    // ... other fields
}
```

---

### What Lombok Generates (Behind the Scenes)

```java
@Entity
@Table(name = "users")
public class User {

    private Long id;
    private String email;
    private String password;
    private String name;
    private Role role;

    public User() {}

    public User(Long id, String email, String password, String name, Role role, ...) {
        this.id = id;
        this.email = email;
        this.password = password;
        this.name = name;
        this.role = role;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPassword() { return password; }

    public void setId(Long id) { this.id = id; }
    public void setEmail(String email) { this.email = email; }

    public static UserBuilder builder() {
        return new UserBuilder();
    }

    public static class UserBuilder {

        private Long id;
        private String email;
        private String password;
        private String name;
        private Role role;
        private Boolean emailVerified;
        private String bio;
        private String avatarUrl;
        private Boolean isDeleted;

        UserBuilder() {}

        public UserBuilder email(String email) {
            this.email = email;
            return this;
        }

        public UserBuilder password(String password) {
            this.password = password;
            return this;
        }

        public UserBuilder name(String name) {
            this.name = name;
            return this;
        }

        public UserBuilder role(Role role) {
            this.role = role;
            return this;
        }

        public User build() {
            return new User(id, email, password, name, role, emailVerified, bio, avatarUrl, isDeleted);
        }
    }
}
```

> 💡 **That’s 200+ lines of code generated from a single `@Builder` annotation!**

---

## 📚 Part 2: How to Use `@Builder`

### Example 1: Create User

#### ❌ Without Builder

```java
User user = new User();
user.setEmail("john@example.com");
user.setPassword("hashedPassword");
user.setName("John Doe");
user.setRole(Role.SUBSCRIBER);
```

#### ✅ With Builder

```java
User user = User.builder()
    .email("john@example.com")
    .password("hashedPassword")
    .name("John Doe")
    .role(Role.SUBSCRIBER)
    .build();
```

### Benefits

* ✅ Highly readable
* ✅ Fluent API (method chaining)
* ✅ Optional fields supported
* ✅ Safer object creation

---

## 📚 Part 3: Default Values & `@Builder.Default`

### ❌ Problem

```java
@Builder
public class User {
    private Boolean emailVerified = false;
}
```

```java
User user = User.builder().build();
System.out.println(user.getEmailVerified()); // null ❌
```

### ✅ Solution

```java
@Builder
public class User {
    @Builder.Default
    private Boolean emailVerified = false;
}
```

```java
User user = User.builder().build();
System.out.println(user.getEmailVerified()); // false ✅
```

---

## 📚 Part 4: Builder Pattern — Step-by-Step

```java
UserBuilder builder = User.builder();
builder = builder.email("john@example.com");
builder = builder.password("hashed");
builder = builder.name("John");
builder = builder.role(Role.SUBSCRIBER);
User user = builder.build();
```

### Fluent API Version

```java
User user = User.builder()
    .email("john@example.com")
    .password("hashed")
    .name("John")
    .role(Role.SUBSCRIBER)
    .build();
```

---

## 📚 Part 5: Real-World Usage

### Auth Service

```java
User user = User.builder()
    .email(request.getEmail())
    .password(hashedPassword)
    .name(request.getName())
    .role(Role.SUBSCRIBER)
    .build();
```

### Tests

```java
User admin = User.builder()
    .email("admin@test.com")
    .role(Role.ADMIN)
    .build();
```

---

## 📚 Part 6: Builder vs Constructors

### Use Builder When

* Many optional fields
* Readability matters
* Creating test data
* Fluent API preferred

### Use Constructor When

* Few required fields
* Simple DTOs

---

## 📚 Part 7: Common Mistakes

### ❌ Forgetting `@Builder.Default`

```java
@Builder
public class User {
    private Boolean isDeleted = false;
}
```

### ❌ Missing `@AllArgsConstructor`

```java
@Builder
public class User {}
```

### ⚠️ Builder + JPA Caution

Avoid setting lifecycle fields manually (`createdAt`, `updatedAt`). Let Hibernate manage them.

---

## 📚 Part 8: Advanced `@Builder` Features

### Custom Builder Method Name

```java
@Builder(builderMethodName = "newUser")
public class User {}
```

```java
User user = User.newUser().email("test").build();
```

### Custom Build Method Name

```java
@Builder(buildMethodName = "create")
public class User {}
```

```java
User user = User.builder().email("test").create();
```

---

## ✅ Final Takeaway

`@Builder` is ideal for:

* Complex domain models
* Test data creation
* Readable & maintainable code

Used correctly, it **reduces bugs, improves clarity, and scales beautifully**.

---

📘 *Recommended for production-level Spring Boot & JPA projects*
