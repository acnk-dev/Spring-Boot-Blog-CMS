package com.aditya_nk.blogcms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClient pointed at blogcms-ai-service. Dispatch calls should return in
 * milliseconds — they only hand off a job, they never wait for Gemini — so
 * the timeout here is deliberately short. A slow ack from the AI service is
 * itself a signal something's wrong, not a sign to wait longer.
 */
@Configuration
public class AIServiceClientConfig {

    @Value("${ai-service.base-url}")
    private String baseUrl;

    @Value("${ai-service.api-key}")
    private String apiKey;

    @Bean
    public RestClient aiServiceRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2_000);
        requestFactory.setReadTimeout(3_000);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("X-Internal-Api-Key", apiKey)
                .build();
    }
}
