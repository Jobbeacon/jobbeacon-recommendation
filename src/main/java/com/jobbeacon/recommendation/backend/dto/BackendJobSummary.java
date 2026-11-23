package com.jobbeacon.recommendation.backend.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A job card as the backend's job list returns it; passed to the frontend unchanged. */
public record BackendJobSummary(
        UUID id,
        String title,
        String companyName,
        Long minSalary,
        Long maxSalary,
        List<String> location,
        String currency,
        String companyLogoUrl,
        Instant postedDate
) {
}
