package com.jobbeacon.recommendation.interaction;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/** The type is a string checked in the service, so an unknown value yields a 400 with an i18n key. */
@Data
public class JobInteractionRequest {

  @NotNull(message = "job_not_found")
  private UUID jobId;

  /** Value of {@code JobInteractionType}. */
  @NotBlank(message = "job_interaction_invalid_type")
  private String type;
}
