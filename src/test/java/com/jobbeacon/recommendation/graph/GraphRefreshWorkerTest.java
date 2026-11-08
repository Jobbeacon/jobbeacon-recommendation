package com.jobbeacon.recommendation.graph;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jobbeacon.recommendation.config.GraphProperties;
import com.jobbeacon.recommendation.queue.GraphEntityType;
import com.jobbeacon.recommendation.queue.GraphSyncQueueItem;
import com.jobbeacon.recommendation.queue.GraphSyncQueueRepository;
import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.neo4j.driver.exceptions.ServiceUnavailableException;

class GraphRefreshWorkerTest {

  private final GraphSyncQueueRepository queueRepository = mock(GraphSyncQueueRepository.class);
  private final GraphSyncService graphSyncService = mock(GraphSyncService.class);
  private final GraphProjectionService graphProjectionService = mock(GraphProjectionService.class);
  private final GraphBackfill graphBackfill = mock(GraphBackfill.class);
  private GraphRefreshWorker worker;

  @BeforeEach
  void setUp() {
    worker = new GraphRefreshWorker(
        queueRepository,
        graphSyncService,
        graphProjectionService,
        graphBackfill,
        new GraphProperties("neo4j", 100, 10, Duration.ofDays(180), true)
    );
  }

  @Test
  void refreshBackfillsThenSyncsJobsThenCandidatesThenRebuildsTheProjection() {
    UUID jobId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    givenPending(GraphEntityType.JOB, row(GraphEntityType.JOB, jobId));
    givenPending(GraphEntityType.CANDIDATE, row(GraphEntityType.CANDIDATE, userId));

    worker.refresh();

    InOrder order = inOrder(graphBackfill, graphSyncService, graphProjectionService);
    order.verify(graphBackfill).runIfNeeded();
    order.verify(graphSyncService).syncJobs(argThat((Collection<UUID> ids) -> ids.contains(jobId)));
    order.verify(graphSyncService).syncCandidates(argThat((Collection<UUID> ids) -> ids.contains(userId)));
    order.verify(graphProjectionService).rebuild();
  }

  @Test
  void rebuildsTheProjectionEvenWithNothingToSync() {
    worker.refresh();

    verify(graphProjectionService).rebuild();
  }

  @Test
  void failedBackfillSkipsTheRunSoItIsRetriedNextTime() {
    doThrow(new RetryableException(-1, "connection refused", Request.HttpMethod.GET, (Long) null, request()))
        .when(graphBackfill).runIfNeeded();

    worker.refresh();

    verifyNoInteractions(queueRepository, graphSyncService, graphProjectionService);
  }

  @Test
  void rowsOfOneUserCollapseIntoOneSync() {
    UUID userId = UUID.randomUUID();
    GraphSyncQueueItem first = row(GraphEntityType.CANDIDATE, userId);
    GraphSyncQueueItem second = row(GraphEntityType.CANDIDATE, userId);
    givenPending(GraphEntityType.CANDIDATE, first, second);

    worker.refresh();

    verify(graphSyncService).syncCandidates(argThat((Collection<UUID> ids) -> ids.size() == 1 && ids.contains(userId)));
    verify(queueRepository).markProcessed(eq(List.of(first.getId(), second.getId())), any());
  }

  @Test
  void neo4jOutageKeepsRowsPendingAndSkipsTheRebuild() {
    givenPending(GraphEntityType.CANDIDATE, row(GraphEntityType.CANDIDATE, UUID.randomUUID()));
    doThrow(new ServiceUnavailableException("down")).when(graphSyncService).syncCandidates(any());

    worker.refresh();

    verify(queueRepository, never()).markProcessed(any(), any());
    verify(queueRepository, never()).recordFailure(any(), any());
    verify(graphProjectionService, never()).rebuild();
  }

  @Test
  void backendOutageKeepsRowsPendingAndSkipsTheRebuild() {
    givenPending(GraphEntityType.JOB, row(GraphEntityType.JOB, UUID.randomUUID()));
    doThrow(feignError(503)).when(graphSyncService).syncJobs(any());

    worker.refresh();

    verify(queueRepository, never()).recordFailure(any(), any());
    verify(queueRepository, never()).findPending(eq(GraphEntityType.CANDIDATE), anyInt(), any());
    verify(graphProjectionService, never()).rebuild();
  }

  @Test
  void backendErrorForOneEntityIsIsolatedLikeAnyOtherFailure() {
    UUID good = UUID.randomUUID();
    UUID bad = UUID.randomUUID();
    GraphSyncQueueItem goodRow = row(GraphEntityType.CANDIDATE, good);
    GraphSyncQueueItem badRow = row(GraphEntityType.CANDIDATE, bad);
    givenPending(GraphEntityType.CANDIDATE, goodRow, badRow);
    doThrow(feignError(500))
        .when(graphSyncService).syncCandidates(argThat((Collection<UUID> ids) -> ids.size() == 2));
    doThrow(new IllegalStateException("bad user")).when(graphSyncService).syncCandidates(Set.of(bad));

    worker.refresh();

    verify(queueRepository).markProcessed(eq(List.of(goodRow.getId())), any());
    verify(queueRepository).recordFailure(eq(List.of(badRow.getId())), eq("bad user"));
    verify(graphProjectionService).rebuild();
  }

  @Test
  void failedRebuildDoesNotEscapeTheScheduledRun() {
    doThrow(new IllegalStateException("out of memory")).when(graphProjectionService).rebuild();

    worker.refresh();

    verify(graphProjectionService).rebuild();
  }

  private void givenPending(GraphEntityType type, GraphSyncQueueItem... rows) {
    when(queueRepository.findPending(eq(type), anyInt(), any())).thenReturn(List.of(rows));
  }

  private static GraphSyncQueueItem row(GraphEntityType type, UUID entityId) {
    GraphSyncQueueItem row = new GraphSyncQueueItem();
    row.setId(UUID.randomUUID());
    row.setEntityType(type);
    row.setEntityId(entityId);
    return row;
  }

  private static Request request() {
    return Request.create(Request.HttpMethod.POST, "http://backend/internal", Map.of(), null, StandardCharsets.UTF_8,
        null);
  }

  private static FeignException feignError(int status) {
    return FeignException.errorStatus("BackendClient#findJobs",
        feign.Response.builder().status(status).reason("error").request(request()).headers(Map.of()).build());
  }
}
