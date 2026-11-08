package com.jobbeacon.recommendation.backend;

import com.jobbeacon.recommendation.config.BackendProperties;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

/** Applied to {@link BackendClient} only (deliberately not a {@code @Configuration}, so it is not picked up globally). */
public class BackendClientConfiguration {

    static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    @Bean
    RequestInterceptor internalTokenInterceptor(BackendProperties properties) {
        return template -> template.header(INTERNAL_TOKEN_HEADER, properties.internalToken());
    }
}
