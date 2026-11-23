package com.jobbeacon.recommendation.recommend;

import com.jobbeacon.recommendation.security.BackendUserResolver;
import com.jobbeacon.recommendation.web.ResponseGeneral;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/recommended-jobs")
@RequiredArgsConstructor
public class RecommendationController {

  private final RecommendationService recommendationService;
  private final BackendUserResolver backendUserResolver;

  /** Every request gives a newly shuffled list. */
  @GetMapping
  public ResponseGeneral<RecommendedJobsResponse> recommend(
      @RequestHeader(BackendUserResolver.USER_ID_HEADER) String uid,
      @RequestParam(defaultValue = "20") int size
  ) {
    return ResponseGeneral.ofSuccess(
        "success",
        recommendationService.recommend(backendUserResolver.requireUserId(uid), size));
  }
}
