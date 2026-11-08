package com.jobbeacon.recommendation.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the backend knows about a set of users. {@code existingUserIds} is separate from the other lists because a user
 * with no skills and no saved jobs still exists, and must not be removed from the graph.
 */
public record BackendCandidates(List<UUID> existingUserIds, List<Skill> skills, List<SavedJob> savedJobs) {

    public record Skill(
            UUID userId,
            UUID skillId,
            String name,
            String normalizedName,
            BigDecimal confidence,
            Integer experienceMonths
    ) {
    }

    public record SavedJob(UUID userId, UUID jobId, Instant savedAt) {
    }
}
