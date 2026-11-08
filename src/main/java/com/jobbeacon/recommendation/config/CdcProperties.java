package com.jobbeacon.recommendation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where the backend's {@code graph_sync_outbox} is streamed from. */
@ConfigurationProperties(prefix = "app.cdc")
public record CdcProperties(
        boolean enabled,
        String sourceUrl,
        String sourceUsername,
        String sourcePassword,
        String slotName,
        String publicationName
) {
}
