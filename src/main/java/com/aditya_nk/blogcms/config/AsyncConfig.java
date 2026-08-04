package com.aditya_nk.blogcms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig implements AsyncConfigurer {

    @Value("${async.core-pool-size:4}")
    private int corePoolSize;

    @Value("${async.max-pool-size:16}")
    private int maxPoolSize;

    @Value("${async.queue-capacity:200}")
    private int queueCapacity;

    @Value("${async.thread-name-prefix:blogcms-async-}")
    private String threadNamePrefix;

    @Bean(name = "taskExecutor")
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        // Caller runs rejected tasks rather than silently dropping them
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated thread pool for @Scheduled tasks.
     *
     * Keeping scheduled jobs on their own threads prevents slow cleanup jobs from
     * starving the @Async pool that handles user-facing email notifications.
     * Pool size 4 is enough for our four scheduled jobs — they run infrequently
     * and are short-lived database operations.
     */
    @Bean(name = "scheduledExecutor")
    public ThreadPoolTaskScheduler scheduledExecutor() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("blogcms-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }
}
