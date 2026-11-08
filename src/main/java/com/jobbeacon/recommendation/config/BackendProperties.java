package com.jobbeacon.recommendation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.backend")
public record BackendProperties(String url, String internalToken) {
}
