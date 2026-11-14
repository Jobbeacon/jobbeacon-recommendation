package com.jobbeacon.recommendation.interaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobbeacon.recommendation.queue.GraphEntityType;
import com.jobbeacon.recommendation.queue.GraphSyncQueueItem;
import com.jobbeacon.recommendation.queue.GraphSyncQueueRepository;
import com.jobbeacon.recommendation.queue.GraphSyncQueueService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real Spring Data queries on a throwaway Postgres built by this service's Liquibase changelog. */
@DataJpaTest
@Testcontainers
@Import(GraphSyncQueueService.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RecommendationRepositoriesIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired
  private UserJobInteractionRepository interactionRepository;
  @Autowired
  private GraphSyncQueueRepository queueRepository;
  @Autowired
  private GraphSyncQueueService queueService;
  @Autowired
  private JdbcTemplate jdbc;

  private final UUID userId = UUID.randomUUID();
  private final UUID jobId = UUID.randomUUID();

  @Test
  void recordCountsOnceWithinTheWindowAndAgainAfterIt() {
    assertThat(record(JobInteractionType.VIEWED)).isOne();
    assertThat(record(JobInteractionType.VIEWED)).isZero();
    assertThat(interactionCount(JobInteractionType.VIEWED)).isOne();

    jdbc.update("UPDATE user_job_interaction SET last_at = now() - interval '31 minutes' WHERE user_id = ?", userId);

    assertThat(record(JobInteractionType.VIEWED)).isOne();
    assertThat(interactionCount(JobInteractionType.VIEWED)).isEqualTo(2);
  }

  @Test
  void recordKeepsEachTypeSeparate() {
    record(JobInteractionType.VIEWED);
    record(JobInteractionType.APPLY_CLICKED);

    assertThat(interactionRepository.findRecentByUserIds(Set.of(userId), Instant.EPOCH))
        .extracting(interaction -> interaction.getId().getInteractionType())
        .containsExactlyInAnyOrder(JobInteractionType.VIEWED, JobInteractionType.APPLY_CLICKED);
  }

  @Test
  void interactionsOutsideTheWindowAreNotRead() {
    record(JobInteractionType.VIEWED);
    jdbc.update("UPDATE user_job_interaction SET last_at = now() - interval '200 days' WHERE user_id = ?", userId);

    assertThat(interactionRepository.findRecentByUserIds(
        Set.of(userId), Instant.now().minus(180, ChronoUnit.DAYS))).isEmpty();
  }

  @Test
  void eachUserWithInteractionsIsListedOnceForTheBackfill() {
    record(JobInteractionType.VIEWED);
    record(JobInteractionType.APPLY_CLICKED);

    assertThat(interactionRepository.findAllUserIds()).containsExactly(userId);
  }

  @Test
  void queuedRowsArePendingByTypeUntilProcessed() {
    queueService.enqueue(GraphEntityType.CANDIDATE, List.of(userId));
    queueService.enqueue(GraphEntityType.JOB, List.of(jobId));

    List<GraphSyncQueueItem> pending = queueRepository.findPending(GraphEntityType.CANDIDATE, 10, PageRequest.of(0, 10));
    assertThat(pending).extracting(GraphSyncQueueItem::getEntityId).containsExactly(userId);

    queueRepository.markProcessed(List.of(pending.get(0).getId()), Instant.now());

    assertThat(queueRepository.findPending(GraphEntityType.CANDIDATE, 10, PageRequest.of(0, 10))).isEmpty();
    assertThat(queueRepository.findPending(GraphEntityType.JOB, 10, PageRequest.of(0, 10))).hasSize(1);
  }

  @Test
  void rowsThatUsedUpTheirAttemptsAreNoLongerPending() {
    queueService.enqueue(GraphEntityType.JOB, List.of(jobId));
    UUID rowId = queueRepository.findPending(GraphEntityType.JOB, 2, PageRequest.of(0, 10)).get(0).getId();

    queueRepository.recordFailure(List.of(rowId), "boom");
    queueRepository.recordFailure(List.of(rowId), "boom");

    assertThat(queueRepository.findPending(GraphEntityType.JOB, 2, PageRequest.of(0, 10))).isEmpty();
  }

  @Test
  void onlyRowsProcessedBeforeTheCutoffAreDeleted() {
    queueService.enqueue(GraphEntityType.JOB, List.of(UUID.randomUUID(), UUID.randomUUID()));
    queueService.enqueue(GraphEntityType.JOB, List.of(jobId));
    List<GraphSyncQueueItem> rows = queueRepository.findPending(GraphEntityType.JOB, 10, PageRequest.of(0, 10));
    UUID old = rows.get(0).getId();
    UUID recent = rows.get(1).getId();
    queueRepository.markProcessed(List.of(old), Instant.now().minus(10, ChronoUnit.DAYS));
    queueRepository.markProcessed(List.of(recent), Instant.now());

    assertThat(queueRepository.deleteProcessedBefore(Instant.now().minus(7, ChronoUnit.DAYS))).isOne();

    assertThat(queueRepository.findById(old)).isEmpty();
    assertThat(queueRepository.findById(recent)).isPresent();
    assertThat(queueRepository.findPending(GraphEntityType.JOB, 10, PageRequest.of(0, 10))).hasSize(1);
  }

  private int record(JobInteractionType type) {
    return interactionRepository.record(userId, jobId, type.name());
  }

  private int interactionCount(JobInteractionType type) {
    return jdbc.queryForObject(
        "SELECT interaction_count FROM user_job_interaction WHERE user_id = ? AND interaction_type = ?",
        Integer.class, userId, type.name());
  }
}
