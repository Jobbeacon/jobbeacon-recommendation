package com.jobbeacon.recommendation.graph;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jobbeacon.recommendation.backend.BackendClient;
import com.jobbeacon.recommendation.backend.dto.BackendIdPage;
import com.jobbeacon.recommendation.config.GraphProperties;
import com.jobbeacon.recommendation.interaction.UserJobInteractionRepository;
import com.jobbeacon.recommendation.queue.GraphEntityType;
import com.jobbeacon.recommendation.queue.GraphSyncQueueService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.exceptions.ServiceUnavailableException;

class GraphBackfillTest {

  private final BackendClient backendClient = mock(BackendClient.class);
  private final UserJobInteractionRepository interactionRepository = mock(UserJobInteractionRepository.class);
  private final GraphSyncQueueService queueService = mock(GraphSyncQueueService.class);
  private final GraphSyncService graphSyncService = mock(GraphSyncService.class);

  @Test
  void queuesEveryJobAndCandidateWhenTheGraphIsEmpty() {
    UUID job = UUID.randomUUID();
    UUID cvUser = UUID.randomUUID();
    UUID interactedUser = UUID.randomUUID();
    when(backendClient.findJobIds(isNull(), anyInt())).thenReturn(new BackendIdPage(List.of(job)));
    when(backendClient.findCandidateIds(isNull(), anyInt())).thenReturn(new BackendIdPage(List.of(cvUser)));
    when(interactionRepository.findAllUserIds()).thenReturn(List.of(interactedUser));

    backfill(true).runIfNeeded();

    verify(queueService).enqueue(GraphEntityType.JOB, List.of(job));
    verify(queueService).enqueue(GraphEntityType.CANDIDATE, List.of(cvUser));
    verify(queueService).enqueue(GraphEntityType.CANDIDATE, List.of(interactedUser));
  }

  @Test
  void pagesThroughTheBackendUsingTheLastIdOfEachPage() {
    List<UUID> fullPage = IntStream.range(0, GraphBackfill.PAGE_SIZE).mapToObj(i -> UUID.randomUUID()).toList();
    UUID last = fullPage.get(fullPage.size() - 1);
    UUID straggler = UUID.randomUUID();
    when(backendClient.findJobIds(isNull(), anyInt())).thenReturn(new BackendIdPage(fullPage));
    when(backendClient.findJobIds(eq(last), anyInt())).thenReturn(new BackendIdPage(List.of(straggler)));
    when(backendClient.findCandidateIds(any(), anyInt())).thenReturn(new BackendIdPage(List.of()));

    backfill(true).runIfNeeded();

    verify(queueService).enqueue(GraphEntityType.JOB, fullPage);
    verify(queueService).enqueue(GraphEntityType.JOB, List.of(straggler));
  }

  @Test
  void skipsWhenTheGraphAlreadyHasData() {
    when(graphSyncService.hasData()).thenReturn(true);

    backfill(true).runIfNeeded();

    verifyNoInteractions(backendClient, queueService);
  }

  @Test
  void doesNothingWhenDisabled() {
    backfill(false).runIfNeeded();

    verifyNoInteractions(graphSyncService, backendClient, queueService);
  }

  @Test
  void runsOnlyOncePerProcess() {
    when(backendClient.findJobIds(any(), anyInt())).thenReturn(new BackendIdPage(List.of()));
    when(backendClient.findCandidateIds(any(), anyInt())).thenReturn(new BackendIdPage(List.of()));
    GraphBackfill backfill = backfill(true);

    backfill.runIfNeeded();
    backfill.runIfNeeded();

    verify(graphSyncService, times(1)).hasData();
  }

  @Test
  void failureLeavesItToBeRetried() {
    when(graphSyncService.hasData()).thenThrow(new ServiceUnavailableException("down")).thenReturn(true);
    GraphBackfill backfill = backfill(true);

    assertThatThrownBy(backfill::runIfNeeded).isInstanceOf(ServiceUnavailableException.class);
    backfill.runIfNeeded();

    verify(graphSyncService, times(2)).hasData();
    verify(queueService, never()).enqueue(any(), any());
  }

  private GraphBackfill backfill(boolean enabled) {
    return new GraphBackfill(backendClient, interactionRepository, queueService, graphSyncService,
        new GraphProperties("neo4j", 100, 10, Duration.ofDays(180), enabled));
  }
}
