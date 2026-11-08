package com.jobbeacon.recommendation.interaction;

import com.jobbeacon.recommendation.backend.BackendClient;
import com.jobbeacon.recommendation.backend.dto.IdsRequest;
import com.jobbeacon.recommendation.queue.GraphSyncQueueService;
import com.jobbeacon.recommendation.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class JobInteractionService {

  static final String INVALID_TYPE = "job_interaction_invalid_type";
  static final String JOB_NOT_FOUND = "job_not_found";

  private final UserJobInteractionRepository interactionRepository;
  private final GraphSyncQueueService graphSyncQueueService;
  private final BackendClient backendClient;
  private final TransactionTemplate transactionTemplate;

  public JobInteractionService(
      UserJobInteractionRepository interactionRepository,
      GraphSyncQueueService graphSyncQueueService,
      BackendClient backendClient,
      PlatformTransactionManager transactionManager
  ) {
    this.interactionRepository = interactionRepository;
    this.graphSyncQueueService = graphSyncQueueService;
    this.backendClient = backendClient;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  public void record(UUID userId, JobInteractionRequest request) {
    JobInteractionType type = parseType(request.getType());
    // Jobs live in the backend; without this check any UUID would become a dangling interaction. Done before the
    // transaction so a slow backend does not hold a database connection.
    if (backendClient.findJobs(new IdsRequest(List.of(request.getJobId()))).isEmpty()) {
      throw ApiException.notFound(JOB_NOT_FOUND);
    }
    // The queue row commits together with the interaction, so a recorded interaction always reaches the graph.
    transactionTemplate.executeWithoutResult(status -> {
      if (interactionRepository.record(userId, request.getJobId(), type.name()) > 0) {
        graphSyncQueueService.candidateChanged(userId);
      }
    });
  }

  private static JobInteractionType parseType(String value) {
    try {
      return JobInteractionType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ApiException.badRequest(INVALID_TYPE);
    }
  }
}
