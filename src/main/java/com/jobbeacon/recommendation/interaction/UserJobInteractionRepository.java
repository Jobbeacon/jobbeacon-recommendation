package com.jobbeacon.recommendation.interaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UserJobInteractionRepository
    extends JpaRepository<UserJobInteraction, UserJobInteraction.UserJobInteractionId> {

  @Modifying
  @Query(
      value = """
          insert into user_job_interaction (user_id, job_id, interaction_type)
          values (:userId, :jobId, :type)
          on conflict (user_id, job_id, interaction_type) do update
          set interaction_count = user_job_interaction.interaction_count + 1,
              last_at = now()
          where user_job_interaction.last_at < now() - interval '30 minutes'
          """,
      nativeQuery = true
  )
  int record(@Param("userId") UUID userId, @Param("jobId") UUID jobId, @Param("type") String type);

  @Query("""
      select i
      from UserJobInteraction i
      where i.id.userId in :userIds and i.lastAt >= :since
      """)
  List<UserJobInteraction> findRecentByUserIds(
      @Param("userIds") Collection<UUID> userIds,
      @Param("since") Instant since
  );

  @Query("select distinct i.id.userId from UserJobInteraction i")
  List<UUID> findAllUserIds();
}
