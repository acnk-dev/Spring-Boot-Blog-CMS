package com.aditya_nk.blogcms.config;

import com.aditya_nk.blogcms.entity.Category;
import com.aditya_nk.blogcms.entity.Post;
import com.aditya_nk.blogcms.entity.Tag;
import com.aditya_nk.blogcms.entity.User;
import com.aditya_nk.blogcms.enums.PostStatus;
import com.aditya_nk.blogcms.enums.Role;
import com.aditya_nk.blogcms.repository.CategoryRepository;
import com.aditya_nk.blogcms.repository.PostRepository;
import com.aditya_nk.blogcms.repository.TagRepository;
import com.aditya_nk.blogcms.repository.UserRepository;
import com.aditya_nk.blogcms.util.SlugUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.Locale;

@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements ApplicationRunner {

    private static final String SEED_PASSWORD = "Password123!";

    private static final String[] TITLE_PREFIXES = {
            "Introduction to", "Deep Dive into", "Getting Started with",
            "Mastering", "Building with", "Understanding", "A Guide to",
            "Working with", "Exploring", "Advanced Techniques in",
            "Best Practices for", "Common Mistakes in", "Scaling with",
            "Securing Your", "Testing Strategies for"
    };

    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final TagRepository tagRepository;
    private final PostRepository postRepository;
    private final PasswordEncoder passwordEncoder;
    private final SeedProperties props;

    private final Faker faker = new Faker(Locale.ENGLISH, new Random(42));
    private final Random random = new Random(42);

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            log.info("DataSeeder: data already exists, skipping.");
            return;
        }

        log.info("DataSeeder: seeding with {} authors, {} subscribers, {} categories, {} tags, {} posts/author",
                props.getAuthors(), props.getSubscribers(), props.getCategories(),
                props.getTags(), props.getPostsPerAuthor());

        List<User> users = seedUsers();
        List<Category> categories = seedCategories();
        List<Tag> tags = seedTags();
        seedPosts(users, categories, tags);
        seedSoftDeletedData(users, categories, tags);

        int totalPosts = (props.getAuthors() + 1) * props.getPostsPerAuthor(); // +1 for admin
        log.info("DataSeeder: done — {} users, {} categories, {} tags, ~{} posts seeded.",
                users.size(), categories.size(), tags.size(), totalPosts);
        log.info("DataSeeder: all seed user password is '{}'", SEED_PASSWORD);
        log.info("DataSeeder: admin login -> admin@blog.com / {}", SEED_PASSWORD);
    }

    // -------------------------------------------------------------------------
    // Users
    // -------------------------------------------------------------------------

    private List<User> seedUsers() {
        String pw = passwordEncoder.encode(SEED_PASSWORD);
        List<User> users = new ArrayList<>();

        users.add(User.builder()
                .email("admin@blog.com")
                .password(pw)
                .name("Admin User")
                .role(Role.ADMIN)
                .emailVerified(true)
                .bio(faker.lorem().sentence(12))
                .avatarUrl("https://i.pravatar.cc/150?u=admin@blog.com")
                .isDeleted(false)
                .build());

        for (int i = 1; i <= props.getAuthors(); i++) {
            String name = faker.name().fullName();
            String email = "author" + i + "@blog.com";
            users.add(User.builder()
                    .email(email)
                    .password(pw)
                    .name(name)
                    .role(Role.AUTHOR)
                    .emailVerified(true)
                    .bio(faker.lorem().sentence(15))
                    .avatarUrl("https://i.pravatar.cc/150?u=" + email)
                    .isDeleted(false)
                    .build());
        }

        for (int i = 1; i <= props.getSubscribers(); i++) {
            String name = faker.name().fullName();
            String email = "subscriber" + i + "@blog.com";
            users.add(User.builder()
                    .email(email)
                    .password(pw)
                    .name(name)
                    .role(Role.SUBSCRIBER)
                    .emailVerified(random.nextBoolean())
                    .bio(faker.lorem().sentence(10))
                    .avatarUrl("https://i.pravatar.cc/150?u=" + email)
                    .isDeleted(false)
                    .build());
        }

        List<User> saved = userRepository.saveAll(users);
        log.info("DataSeeder: saved {} users", saved.size());
        return saved;
    }

    // -------------------------------------------------------------------------
    // Categories
    // -------------------------------------------------------------------------

    private List<Category> seedCategories() {
        // Fixed realistic category pool — pick up to props.getCategories() from it
        List<String[]> pool = List.of(
                new String[]{"Technology", "Emerging trends and innovations shaping the tech industry."},
                new String[]{"Web Development", "Frontend, backend, and full-stack topics for modern web engineers."},
                new String[]{"Mobile Development", "iOS, Android, and cross-platform mobile app development."},
                new String[]{"DevOps & Cloud", "CI/CD, containers, orchestration, and cloud infrastructure."},
                new String[]{"Data Science", "Data analysis, machine learning, and AI engineering."},
                new String[]{"Career & Growth", "Advice on growing as a software engineer and navigating your career."},
                new String[]{"Open Source", "Contributing to and building open source software."},
                new String[]{"Security", "Application security, penetration testing, and secure coding practices."},
                new String[]{"Design & UX", "UI design principles, accessibility, and user experience research."},
                new String[]{"Engineering Culture", "Team dynamics, agile practices, and building great engineering teams."},
                new String[]{"Databases", "SQL, NoSQL, query optimization, and data modeling."},
                new String[]{"Testing", "Unit, integration, and end-to-end testing strategies."},
                new String[]{"Performance", "Profiling, benchmarking, and optimization techniques."},
                new String[]{"System Design", "Architecture patterns for scalable and resilient systems."},
                new String[]{"Tooling", "IDEs, CLI tools, editors, and developer productivity."}
        );

        int count = Math.min(props.getCategories(), pool.size());
        List<Category> categories = pool.subList(0, count).stream().map(d -> Category.builder()
                .name(d[0])
                .slug(SlugUtil.generateSlug(d[0]))
                .description(d[1])
                .build()
        ).toList();

        List<Category> saved = categoryRepository.saveAll(categories);
        log.info("DataSeeder: saved {} categories", saved.size());
        return saved;
    }

    // -------------------------------------------------------------------------
    // Tags
    // -------------------------------------------------------------------------

    private List<Tag> seedTags() {
        // Start with a curated tech tag pool, then fill with faker if more are needed
        List<String> pool = new ArrayList<>(List.of(
                "Java", "Spring Boot", "PostgreSQL", "React", "Docker",
                "Kubernetes", "AWS", "TypeScript", "Python", "Machine Learning",
                "REST API", "Microservices", "Git", "Linux", "Security",
                "Testing", "Performance", "Open Source", "Career", "Design Patterns",
                "GraphQL", "Redis", "CI/CD", "Rust", "Go",
                "Vue.js", "Node.js", "MongoDB", "Kafka", "gRPC"
        ));

        Set<String> uniqueNames = new LinkedHashSet<>(pool.subList(0, Math.min(props.getTags(), pool.size())));

        // If more tags are needed than the curated list, generate from faker
        while (uniqueNames.size() < props.getTags()) {
            String candidate = faker.programmingLanguage().name();
            uniqueNames.add(candidate);
        }

        List<Tag> tags = uniqueNames.stream().map(name -> Tag.builder()
                .name(name)
                .slug(SlugUtil.generateSlug(name))
                .build()
        ).toList();

        List<Tag> saved = tagRepository.saveAll(tags);
        log.info("DataSeeder: saved {} tags", saved.size());
        return saved;
    }

    // -------------------------------------------------------------------------
    // Posts
    // -------------------------------------------------------------------------

    private void seedPosts(List<User> users, List<Category> categories, List<Tag> tags) {
        List<User> eligibleAuthors = users.stream()
                .filter(u -> u.getRole() == Role.AUTHOR || u.getRole() == Role.ADMIN)
                .toList();

        List<Post> posts = new ArrayList<>();

        for (int authorIdx = 0; authorIdx < eligibleAuthors.size(); authorIdx++) {
            User author = eligibleAuthors.get(authorIdx);

            for (int i = 0; i < props.getPostsPerAuthor(); i++) {
                String title = generateTitle();
                // Append indices to guarantee slug uniqueness within the batch
                // (postRepository.existsBySlug would not see unsaved in-transaction posts)
                String slug = SlugUtil.generateSlug(title) + "-" + authorIdx + "-" + i;

                boolean published = random.nextInt(10) < 6; // 60% published
                PostStatus status = published ? PostStatus.PUBLISHED : PostStatus.DRAFT;
                LocalDateTime publishedAt = published
                        ? LocalDateTime.now().minusDays(random.nextInt(730) + 1)
                        : null;

                List<Tag> shuffled = new ArrayList<>(tags);
                Collections.shuffle(shuffled, random);
                Set<Tag> postTags = new HashSet<>(shuffled.subList(0, random.nextInt(4) + 1));

                Category category = categories.get(random.nextInt(categories.size()));

                posts.add(Post.builder()
                        .user(author)
                        .category(category)
                        .title(title)
                        .slug(slug)
                        .content(generateContent(title))
                        .excerpt(faker.lorem().sentence(12))
                        .featuredImageUrl("https://picsum.photos/seed/" + Math.abs(slug.hashCode()) + "/800/400")
                        .status(status)
                        .viewCount(published ? random.nextInt(5000) : 0)
                        .likeCount(published ? random.nextInt(500) : 0)
                        .tags(postTags)
                        .isDeleted(false)
                        .publishedAt(publishedAt)
                        .build());
            }
        }

        postRepository.saveAll(posts);
        log.info("DataSeeder: saved {} posts", posts.size());
    }

    // -------------------------------------------------------------------------
    // Soft-deleted test data (for testing CleanupService scheduled jobs)
    // -------------------------------------------------------------------------

    private void seedSoftDeletedData(List<User> activeUsers, List<Category> categories, List<Tag> tags) {
        if (userRepository.countByIsDeletedTrue() > 0) {
            log.info("DataSeeder: soft-deleted test data already exists, skipping.");
            return;
        }

        String pw = passwordEncoder.encode(SEED_PASSWORD);

        // 3 soft-deleted users — deleted 45 days ago (beyond the 30-day retention window → eligible for purge)
        List<User> deletedUsers = userRepository.saveAll(List.of(
                User.builder().email("deleted-author1@blog.com").password(pw)
                        .name("Deleted Author One").role(Role.AUTHOR)
                        .emailVerified(true).isDeleted(true)
                        .bio("This account was soft-deleted 45 days ago.").build(),
                User.builder().email("deleted-subscriber1@blog.com").password(pw)
                        .name("Deleted Subscriber One").role(Role.SUBSCRIBER)
                        .emailVerified(false).isDeleted(true)
                        .bio("This account was soft-deleted 45 days ago.").build(),
                User.builder().email("deleted-subscriber2@blog.com").password(pw)
                        .name("Deleted Subscriber Two").role(Role.SUBSCRIBER)
                        .emailVerified(true).isDeleted(true)
                        .bio("This account was soft-deleted 45 days ago.").build()
        ));

        // Push their updatedAt back 45 days so the cleanup job treats them as purgeable
        LocalDateTime deletedAt = LocalDateTime.now().minusDays(45);
        userRepository.flush();
        deletedUsers.forEach(u -> userRepository.backdateUpdatedAt(u.getId(), deletedAt));

        // 1 soft-deleted user deleted only 10 days ago — inside retention window, must NOT be purged
        User recentlyDeleted = userRepository.save(
                User.builder().email("recently-deleted@blog.com").password(pw)
                        .name("Recently Deleted User").role(Role.SUBSCRIBER)
                        .emailVerified(true).isDeleted(true)
                        .bio("Deleted 10 days ago — still within the 30-day retention window.").build()
        );
        userRepository.flush();
        userRepository.backdateUpdatedAt(recentlyDeleted.getId(), LocalDateTime.now().minusDays(10));

        // Soft-deleted posts: 3 old (purgeable) + 1 recent (retain)
        User author = activeUsers.stream()
                .filter(u -> u.getRole() == Role.AUTHOR)
                .findFirst()
                .orElse(activeUsers.get(0));
        Category category = categories.get(0);
        Set<Tag> postTags = new HashSet<>(tags.subList(0, 2));

        List<Post> deletedPosts = postRepository.saveAll(List.of(
                Post.builder().user(author).category(category).tags(postTags)
                        .title("Soft-Deleted Post Alpha").slug("soft-deleted-post-alpha")
                        .content("This post was soft-deleted 60 days ago and is eligible for hard-delete.")
                        .excerpt("Eligible for cleanup.").status(PostStatus.DRAFT)
                        .isDeleted(true).viewCount(0).likeCount(0).build(),
                Post.builder().user(author).category(category).tags(postTags)
                        .title("Soft-Deleted Post Beta").slug("soft-deleted-post-beta")
                        .content("This post was soft-deleted 50 days ago and is eligible for hard-delete.")
                        .excerpt("Eligible for cleanup.").status(PostStatus.DRAFT)
                        .isDeleted(true).viewCount(0).likeCount(0).build(),
                Post.builder().user(author).category(category).tags(postTags)
                        .title("Soft-Deleted Post Gamma").slug("soft-deleted-post-gamma")
                        .content("This post was soft-deleted 35 days ago and is eligible for hard-delete.")
                        .excerpt("Eligible for cleanup.").status(PostStatus.DRAFT)
                        .isDeleted(true).viewCount(0).likeCount(0).build()
        ));
        postRepository.flush();
        deletedPosts.forEach(p -> postRepository.backdateUpdatedAt(p.getId(), LocalDateTime.now().minusDays(35 + random.nextInt(25))));

        Post recentPost = postRepository.save(
                Post.builder().user(author).category(category).tags(postTags)
                        .title("Recently Soft-Deleted Post").slug("recently-soft-deleted-post")
                        .content("Deleted only 5 days ago — must NOT be purged by the cleanup job.")
                        .excerpt("Within retention window.").status(PostStatus.DRAFT)
                        .isDeleted(true).viewCount(0).likeCount(0).build()
        );
        postRepository.flush();
        postRepository.backdateUpdatedAt(recentPost.getId(), LocalDateTime.now().minusDays(5));

        log.info("DataSeeder: seeded {} soft-deleted users and {} soft-deleted posts for cleanup job testing.",
                deletedUsers.size() + 1, deletedPosts.size() + 1);
        log.info("DataSeeder: cleanup job should purge 3 users + 3 posts (>30 days old); retain 1 user + 1 post (<30 days).");
    }

    // -------------------------------------------------------------------------
    // Content generation helpers
    // -------------------------------------------------------------------------

    private String generateTitle() {
        String prefix = TITLE_PREFIXES[random.nextInt(TITLE_PREFIXES.length)];
        String subject = faker.programmingLanguage().name();
        return prefix + " " + subject;
    }

    private String generateContent(String title) {
        String intro = faker.lorem().sentence(8) + " This article covers **" + title + "** in depth. "
                + faker.lorem().paragraph(3);
        String section1Body = faker.lorem().paragraph(6);
        String section2Body = faker.lorem().paragraph(5);
        String section3Body = faker.lorem().paragraph(6);
        String conclusion = faker.lorem().paragraph(3);

        String section1Title = capitalize(faker.lorem().sentence(3, 5));
        String section2Title = capitalize(faker.lorem().sentence(3, 5));
        String section3Title = capitalize(faker.lorem().sentence(3, 5));

        return """
                ## Introduction

                %s

                ## %s

                %s

                ## %s

                %s

                ## %s

                %s

                ## Conclusion

                %s
                """.formatted(
                intro,
                section1Title, section1Body,
                section2Title, section2Body,
                section3Title, section3Body,
                conclusion
        );
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        String trimmed = s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
        return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
    }
}