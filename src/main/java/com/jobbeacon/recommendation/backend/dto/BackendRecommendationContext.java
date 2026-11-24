package com.jobbeacon.recommendation.backend.dto;

import java.util.List;
import java.util.UUID;

/** The user's saved jobs as of now, and how the requested jobs fit the job preference they filled in (or their CV). */
public record BackendRecommendationContext(List<UUID> savedJobIds, List<PreferenceMatch> matches) {

    public enum CvTitle {
        CURRENT,
        PAST
    }

    /**
     * Each field is null when the user stated no such preference or the job does not say. {@code cvTitle} is only set
     * while the user states no desired title and the job matches a title in their CV. {@code missingExperienceMonths} is
     * what the job asks for beyond the CV's experience, 0 when it is enough.
     */
    public record PreferenceMatch(
            UUID jobId,
            Boolean title,
            Boolean location,
            Boolean employmentType,
            Boolean salary,
            CvTitle cvTitle,
            Integer missingExperienceMonths
    ) {
    }

    public record Request(UUID userId, List<UUID> jobIds) {
    }

    public record TitleJobsRequest(UUID userId, int limit) {
    }
}
