package com.jobbeacon.recommendation.backend.dto;

import java.util.List;
import java.util.UUID;

/** The user's saved jobs as of now, and how the requested jobs fit their job preference and CV. */
public record BackendRecommendationContext(List<UUID> savedJobIds, List<PreferenceMatch> matches) {

    /**
     * Each field is null when the user stated no such preference or the job does not say. {@code missingExperienceMonths}
     * is what the job asks for beyond the CV's experience, 0 when it is enough. Titles are not here: jobs found by title
     * are a source of their own (see {@link BackendContentJobs}).
     */
    public record PreferenceMatch(
            UUID jobId,
            Boolean location,
            Boolean employmentType,
            Boolean salary,
            Integer missingExperienceMonths
    ) {
    }

    public record Request(UUID userId, List<UUID> jobIds) {
    }
}
