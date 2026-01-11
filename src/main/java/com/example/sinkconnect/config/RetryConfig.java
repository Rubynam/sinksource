package com.example.sinkconnect.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

@Configuration
public class RetryConfig {

    @Value("${sink-connector.retry.max-attempts}")
    private int maxAttempts;

    @Value("${sink-connector.retry.initial-backoff-ms}")
    private long initialBackoffMs;

    @Value("${sink-connector.retry.max-backoff-ms}")
    private long maxBackoffMs;

    @Value("${sink-connector.retry.backoff-multiplier}")
    private double backoffMultiplier;

    @Bean
    public RetryTemplate retryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(maxAttempts);
        retryTemplate.setRetryPolicy(retryPolicy);

        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(initialBackoffMs);
        backOffPolicy.setMaxInterval(maxBackoffMs);
        backOffPolicy.setMultiplier(backoffMultiplier);
        retryTemplate.setBackOffPolicy(backOffPolicy);

        return retryTemplate;
    }
}