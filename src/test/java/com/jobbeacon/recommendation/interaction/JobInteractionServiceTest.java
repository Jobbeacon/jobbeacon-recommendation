package com.jobbeacon.recommendation.interaction;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jobbeacon.recommendation.backend.BackendClient;
import com.jobbeacon.recommendation.backend.dto.BackendJob;
import com.jobbeacon.recommendation.queue.GraphSyncQueueService;
import com.jobbeacon.recommendation.web.ApiException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class JobInteractionServiceTest {

  private static final UUID USER = UUID.randomUUID();
  private static final UUID JOB = UUID.randomUUID();

  private final UserJobInteractionRepository interactionRepository = mock(UserJobInteractionRepository.class);
  private final GraphSyncQueueService queueService = mock(GraphSyncQueueService.class);
  private final BackendClient backendClient = mock(BackendClient.class);
  private final JobInteractionService service =
      new JobInteractionService(interactionRepository, queueService, backendClient, new NoOpTransactionManager());

  @Test
  void countedInteractionQueuesTheCandidate() {
    givenJobExists();
    when(interactionRepository.record(USER, JOB, "APPLY_CLICKED")).thenReturn(1);

    service.record(USER, request("apply_clicked"));

    verify(queueService).candidateChanged(USER);
  }

  @Test
  void repeatWithinTheWindowQueuesNothing() {
    givenJobExists();
    when(interactionRepository.record(USER, JOB, "VIEWED")).thenReturn(0);

    service.record(USER, request("VIEWED"));

    verify(queueService, never()).candidateChanged(any());
  }

  @Test
  void unknownJobIsRejectedWithoutRecording() {
    when(backendClient.findJobs(any())).thenReturn(List.of());

    assertThatThrownBy(() -> service.record(USER, request("VIEWED")))
        .isInstanceOf(ApiException.class)
        .hasMessage(JobInteractionService.JOB_NOT_FOUND);
    verify(interactionRepository, never()).record(any(), any(), anyString());
  }

  @Test
  void unknownTypeIsRejectedBeforeCallingTheBackend() {
    assertThatThrownBy(() -> service.record(USER, request("CLICKED")))
        .isInstanceOf(ApiException.class)
        .hasMessage(JobInteractionService.INVALID_TYPE);
    verifyNoInteractions(backendClient, interactionRepository);
  }

  private void givenJobExists() {
    when(backendClient.findJobs(any())).thenReturn(List.of(
        new BackendJob(JOB, "Backend", null, null, null, null, List.of(), List.of())));
  }

  private static JobInteractionRequest request(String type) {
    JobInteractionRequest request = new JobInteractionRequest();
    request.setJobId(JOB);
    request.setType(type);
    return request;
  }

  private static class NoOpTransactionManager extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
    }
  }
}
