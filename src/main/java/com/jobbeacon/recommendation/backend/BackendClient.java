package com.jobbeacon.recommendation.backend;

import com.jobbeacon.recommendation.backend.dto.BackendCandidates;
import com.jobbeacon.recommendation.backend.dto.BackendContentJobs;
import com.jobbeacon.recommendation.backend.dto.BackendIdPage;
import com.jobbeacon.recommendation.backend.dto.BackendJob;
import com.jobbeacon.recommendation.backend.dto.BackendJobSummary;
import com.jobbeacon.recommendation.backend.dto.BackendRecommendationContext;
import com.jobbeacon.recommendation.backend.dto.BackendUser;
import com.jobbeacon.recommendation.backend.dto.IdsRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

@FeignClient(name = "${app.backend.service-id}", contextId = "backend", path = "${app.backend.context-path}")
public interface BackendClient {

    /** Fails with {@code FeignException.NotFound} when no user has signed in with that Firebase account yet. */
    @GetMapping("/internal/v1/users/by-firebase-uid/{firebaseUid}")
    BackendUser findUserByFirebaseUid(@PathVariable("firebaseUid") String firebaseUid);

    /** Jobs that no longer exist are left out of the result. */
    @PostMapping("/internal/v1/graph/jobs")
    List<BackendJob> findJobs(@RequestBody IdsRequest request);

    @PostMapping("/internal/v1/graph/candidates")
    BackendCandidates findCandidates(@RequestBody IdsRequest request);

    /** Cards of the given jobs for display. Missing and expired jobs are left out, and the order is unspecified. */
    @PostMapping("/internal/v1/jobs/summaries")
    List<BackendJobSummary> findJobSummaries(@RequestBody IdsRequest request);

    @PostMapping("/internal/v1/recommendation/context")
    BackendRecommendationContext findRecommendationContext(@RequestBody BackendRecommendationContext.Request request);

    /** Open jobs found from the titles the user looks for and from what their CV is about, each list best first. */
    @PostMapping("/internal/v1/recommendation/content-jobs")
    BackendContentJobs findContentJobs(@RequestBody BackendContentJobs.Request request);

    /** Job ids in ascending order, starting after {@code after}; for the backfill. */
    @GetMapping("/internal/v1/graph/job-ids")
    BackendIdPage findJobIds(@RequestParam(value = "after", required = false) UUID after, @RequestParam("limit") int limit);

    /** Ids of users with a CV or saved jobs, in ascending order, starting after {@code after}; for the backfill. */
    @GetMapping("/internal/v1/graph/candidate-ids")
    BackendIdPage findCandidateIds(
        @RequestParam(value = "after", required = false) UUID after,
        @RequestParam("limit") int limit
    );
}
