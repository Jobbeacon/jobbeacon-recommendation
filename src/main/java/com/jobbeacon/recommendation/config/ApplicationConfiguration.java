package com.jobbeacon.recommendation.config;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Kept off the main class so that test slices (e.g. {@code @DataJpaTest}) do not start Feign or the scheduler. */
@Configuration
@EnableScheduling
@EnableFeignClients(basePackages = "com.jobbeacon.recommendation.backend")
public class ApplicationConfiguration {
}
