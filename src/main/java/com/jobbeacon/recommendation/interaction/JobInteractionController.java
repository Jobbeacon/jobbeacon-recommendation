package com.jobbeacon.recommendation.interaction;

import com.jobbeacon.recommendation.web.ResponseGeneral;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me/job-interactions")
@RequiredArgsConstructor
public class JobInteractionController {

  private final JobInteractionService jobInteractionService;

  /** The principal is the backend user id, resolved from the Firebase token by {@code FirebaseTokenFilter}. */
  @PostMapping
  public ResponseGeneral<Void> record(@AuthenticationPrincipal UUID userId, @Valid @RequestBody JobInteractionRequest request) {
    jobInteractionService.record(userId, request);
    return ResponseGeneral.ofSuccess("success");
  }
}
