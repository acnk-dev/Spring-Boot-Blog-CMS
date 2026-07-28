# Commit 1: Initial Spring Boot Project Setup for Blog CMS

**Commit Hash:** `9318fb3`
**Date:** January 13, 2026
**Commit Message:** Initial Spring Boot project setup for Blog CMS

---

## Development Mindset & Decision Process

### The Challenge
Starting a new Spring Boot project from scratch for a Blog Content Management System. We need a solid foundation that will support user authentication, content management, and role-based access control.

### Key Decisions Made

#### 1. Spring Boot Version: 4.0.1
**Why this version?**
- Latest stable release of Spring Boot
- Uses Spring Framework 6.x under the hood
- Built for Java 17+ (we're using Java 21)
- Modern features and improved performance
- Long-term support and active maintenance

**Teaching Point:** Always start with the latest stable version unless you have specific compatibility requirements. Spring Boot 4.x is a major upgrade that embraces modern Java features.

#### 2. Java 21
**Why Java 21?**
- Latest LTS (Long Term Support) version
- Virtual threads (Project Loom) - better concurrency handling
- Pattern matching enhancements
- Record patterns
- Modern language features make code more readable and maintainable

**Teaching Point:** LTS versions (8, 11, 17, 21) are preferred for production applications. Java 21 is particularly exciting because of virtual threads, which will help when we scale our blog to handle many concurrent users.

#### 3. Initial Dependencies Selected

**Spring Boot Starter Web**
- **Purpose:** Build REST APIs and web services
- **Why needed:** Our blog CMS will expose REST endpoints for the frontend
- **What it includes:** Spring MVC, Tomcat (embedded server), JSON processing (Jackson)

**Spring Boot Starter Data JPA**
- **Purpose:** Database access layer with Object-Relational Mapping (ORM)
- **Why needed:** We'll need to store users, posts, comments, categories, etc.
- **What it includes:** Hibernate, Spring Data JPA, Transaction management
- **Teaching Point:** JPA abstracts database operations - we write Java code, not SQL (though SQL is still important to understand!)

**PostgreSQL Driver**
- **Purpose:** Connect to PostgreSQL database
- **Why PostgreSQL over MySQL/H2?**
    - Open-source and robust
    - Better support for complex data types (JSON, arrays)
    - Excellent for content management systems
    - Great documentation and community
    - Production-ready out of the box

**Spring Boot Starter Security**
- **Purpose:** Authentication and authorization
- **Why needed from day 1:**
    - Security is NOT an afterthought
    - Blog CMS needs user roles (Subscriber, Author, Admin)
    - Better to build with security from the start than retrofit later
- **Teaching Point:** Including Security early forces us to think about access control as we build features

**Spring Boot Starter Validation**
- **Purpose:** Bean validation (JSR-303/380)
- **Why needed:** Validate user input before it reaches our business logic
- **Examples:** Email format validation, password strength, required fields
- **Teaching Point:** Never trust user input - validate at multiple layers

### Project Structure Created

```
blog-cms/
├── .mvn/wrapper/          # Maven wrapper for consistent builds across machines
├── mvnw & mvnw.cmd        # Maven wrapper scripts (Unix & Windows)
├── pom.xml                # Maven project configuration
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com.studylink.blogcms/
│   │   │       └── BlogCmsApplication.java    # Main entry point
│   │   └── resources/
│   │       └── application.properties         # Configuration file
│   └── test/
│       └── java/
│           └── com.studylink.blogcms/
│               └── BlogCmsApplicationTests.java
└── .gitignore             # Files to exclude from version control
```

### Maven Wrapper - Why?
**Problem:** Different developers might have different Maven versions installed.
**Solution:** Maven wrapper ensures everyone uses the same Maven version.
**Usage:** `./mvnw` instead of `mvn`

**Teaching Point:** This is a best practice for team projects. It eliminates "works on my machine" problems related to build tools.

### The Main Application Class

```java
@SpringBootApplication
public class BlogCmsApplication {
    public static void main(String[] args) {
        SpringApplication.run(BlogCmsApplication.class, args);
    }
}
```

**What @SpringBootApplication does:**
1. `@Configuration` - Marks class as source of bean definitions
2. `@EnableAutoConfiguration` - Spring Boot's magic - auto-configures based on classpath
3. `@ComponentScan` - Scans for components in this package and sub-packages

**Teaching Point:** This single annotation is incredibly powerful. Spring Boot will:
- Configure embedded Tomcat
- Set up database connection pool
- Configure JPA/Hibernate
- Set up security defaults
- And much more - all from this one annotation!

### What Can We Do After This Commit?

**We can:**
- Run the application: `./mvnw spring-boot:run`
- Application starts on port 8080
- Hit any endpoint → gets 401 Unauthorized (Security is active!)
- Spring Security is protecting everything by default

**We cannot:**
- Access any endpoints (Security blocks everything)
- Connect to database (not configured yet)
- Store any data (no entities or tables yet)

### Next Steps Preview

After this foundation, we need to:
1. Add specialized dependencies (JWT for auth, Flyway for database migrations, Lombok for cleaner code)
2. Configure database connection
3. Create our first entity (User)
4. Set up authentication mechanism

---

## Teaching Talking Points

### For Students New to Spring Boot:
- "Spring Boot is 'opinionated' - it makes smart defaults for us"
- "Convention over configuration - if we follow patterns, we write less boilerplate"
- "The power is in the starters - each starter brings a complete set of dependencies"

### For Students Coming from Express/Django:
- Spring Boot is like Express, but enterprise-grade with more structure
- It's opinionated like Django, but with more flexibility
- Configuration through annotations (like Python decorators)

### Common Questions to Anticipate:
**Q: Why not Spring Framework alone?**
A: Spring Boot = Spring Framework + Auto-configuration + Embedded server + Production-ready features. Less XML, less configuration, faster development.

**Q: Why Maven over Gradle?**
A: Both are great. Maven is more widespread, better for beginners (XML is verbose but readable), more enterprise adoption. Gradle is faster and more flexible.

**Q: Can we start without Security?**
A: We could, but it's a bad habit. Adding security later is much harder than building with it from the start.

---

## Commit Stats
- **Files created:** 9
- **Lines added:** 637
- **Time invested:** ~30 minutes (using Spring Initializr speeds this up!)

---

## Resources for Students
- Spring Boot Reference Documentation: https://docs.spring.io/spring-boot/
- Spring Initializr (project generator): https://start.spring.io/
- Baeldung Spring Boot tutorials: https://www.baeldung.com/spring-boot