package com.jobbeacon.recommendation.interaction;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "user_job_interaction")
public class UserJobInteraction {

  @EmbeddedId
  private UserJobInteractionId id;

  @Column(name = "interaction_count", nullable = false)
  private int interactionCount;

  @Column(name = "first_at", nullable = false)
  private Instant firstAt;

  @Column(name = "last_at", nullable = false)
  private Instant lastAt;

  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @EqualsAndHashCode
  @Embeddable
  public static class UserJobInteractionId implements Serializable {
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "job_id")
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "interaction_type", length = 20)
    private JobInteractionType interactionType;
  }
}
