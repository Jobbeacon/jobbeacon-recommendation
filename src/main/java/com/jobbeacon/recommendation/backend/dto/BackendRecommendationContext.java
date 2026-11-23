package com.jobbeacon.recommendation.backend.dto;

import java.util.List;
import java.util.UUID;

/** The user's saved jobs as of now, and how the requested jobs fit the job preference they filled in. */
public record BackendRecommendationContext(List<UUID> savedJobIds, List<PreferenceMatch> matches) {

    /** Each field is null when the user stated no such preference or the job does not say. */
    public record PreferenceMatch(
            UUID jobId,
            Boolean title,
            Boolean location,
            Boolean employmentType,
            Boolean salary
    ) {
    }

    public record Request(UUID userId, List<UUID> jobIds) {
    }
}
