package com.jobbeacon.recommendation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.graph")
public record GraphProperties(
        String database,
        int batchSize,
        int maxAttempts,
        Duration interactionWindow,
        boolean backfillOnStartup
) {
}
