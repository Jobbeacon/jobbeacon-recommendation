package com.jobbeacon.recommendation.backend.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BackendJob(
        UUID id,
        String title,
        UUID companyId,
        String companyName,
        Instant postedDate,
        Instant expiredDate,
        List<Skill> skills,
        List<Source> sources
) {

    public record Skill(UUID id, String name, String normalizedName) {
    }

    public record Source(String domain, String jobUrl) {
    }
}
