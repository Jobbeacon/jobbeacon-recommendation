package com.jobbeacon.recommendation.recommend;

import com.jobbeacon.recommendation.backend.dto.BackendJobSummary;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A user's recommended jobs, in a different random order on every request. There are no pages: a second page drawn
 * from another order would repeat and skip jobs.
 *
 * @param content       job cards, in the same shape as the backend's job list
 * @param personalized  false when nothing about the user shaped the list: the graph knows too little about them (no CV,
 *                      saves or interactions) and no job matches a preference they filled in
 * @param matchedSkills for the jobs that ask for skills in the user's CV, some of those skills
 */
public record RecommendedJobsResponse(
        List<BackendJobSummary> content,
        boolean personalized,
        Map<UUID, List<String>> matchedSkills
) {
}
