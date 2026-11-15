package com.jobbeacon.recommendation.interaction;

import com.jobbeacon.recommendation.security.BackendUserResolver;
import com.jobbeacon.recommendation.web.ResponseGeneral;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/job-interactions")
@RequiredArgsConstructor
public class JobInteractionController {

  private final JobInteractionService jobInteractionService;
  private final BackendUserResolver backendUserResolver;

  @PostMapping
  public ResponseGeneral<Void> record(
      @RequestHeader(BackendUserResolver.USER_ID_HEADER) String uid,
      @Valid @RequestBody JobInteractionRequest request
  ) {
    jobInteractionService.record(backendUserResolver.requireUserId(uid), request);
    return ResponseGeneral.ofSuccess("success");
  }
}
