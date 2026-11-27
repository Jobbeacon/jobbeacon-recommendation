package com.jobbeacon.recommendation.backend.dto;

import java.util.List;
import java.util.UUID;

/**
 * Open jobs the backend found from what the user looks for, each list best first.
 *
 * @param titleJobIds    with the words of a title they look for (or, if they stated none, one from their CV)
 * @param semanticJobIds closest in meaning to their desired titles and CV; empty until the backend has embedded them
 */
public record BackendContentJobs(List<UUID> titleJobIds, List<UUID> semanticJobIds) {

    public static final BackendContentJobs NONE = new BackendContentJobs(List.of(), List.of());

    public BackendContentJobs {
        titleJobIds = titleJobIds == null ? List.of() : titleJobIds;
        semanticJobIds = semanticJobIds == null ? List.of() : semanticJobIds;
    }

    /** How many jobs to find for each source; 0 skips it. */
    public record Request(UUID userId, int titleLimit, int semanticLimit) {
    }
}
