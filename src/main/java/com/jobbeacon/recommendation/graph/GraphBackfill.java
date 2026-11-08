package com.jobbeacon.recommendation.graph;

import com.jobbeacon.recommendation.backend.BackendClient;
import com.jobbeacon.recommendation.backend.dto.BackendIdPage;
import com.jobbeacon.recommendation.config.GraphProperties;
import com.jobbeacon.recommendation.interaction.UserJobInteractionRepository;
import com.jobbeacon.recommendation.queue.GraphEntityType;
import com.jobbeacon.recommendation.queue.GraphSyncQueueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Seeds an empty graph with data that existed before the outbox did, by queueing every job and every user with a CV,
 * saved jobs or interactions; {@link GraphRefreshWorker} then syncs them in batches.
 *
 * <p>Runs at most once per process, and only when {@code app.graph.backfill-on-startup} is on and the graph has no
 * nodes yet, so restarts do not re-sync everything. A failure (backend or Neo4j down) leaves it to be retried on the
 * next refresh; whatever it queued before failing is harmless to queue again.
 */
@Slf4j
@Component
public class GraphBackfill {

    static final int PAGE_SIZE = 1000;

    private final BackendClient backendClient;
    private final UserJobInteractionRepository interactionRepository;
    private final GraphSyncQueueService queueService;
    private final GraphSyncService graphSyncService;
    private volatile boolean done;

    public GraphBackfill(
        BackendClient backendClient,
        UserJobInteractionRepository interactionRepository,
        GraphSyncQueueService queueService,
        GraphSyncService graphSyncService,
        GraphProperties properties
    ) {
        this.backendClient = backendClient;
        this.interactionRepository = interactionRepository;
        this.queueService = queueService;
        this.graphSyncService = graphSyncService;
        this.done = !properties.backfillOnStartup();
    }

    public void runIfNeeded() {
        if (done) {
            return;
        }
        if (graphSyncService.hasData()) {
            log.info("Graph already has data, skipping graph backfill");
            done = true;
            return;
        }
        int jobs = enqueueAll(GraphEntityType.JOB, backendClient::findJobIds);
        int candidates = enqueueAll(GraphEntityType.CANDIDATE, backendClient::findCandidateIds);
        List<UUID> interacted = interactionRepository.findAllUserIds();
        queueService.enqueue(GraphEntityType.CANDIDATE, interacted);
        done = true;
        log.info("Queued {} jobs and {} candidates for graph backfill", jobs, candidates + interacted.size());
    }

    private int enqueueAll(GraphEntityType type, BiFunction<UUID, Integer, BackendIdPage> fetchPage) {
        int total = 0;
        UUID after = null;
        List<UUID> ids;
        do {
            ids = fetchPage.apply(after, PAGE_SIZE).ids();
            queueService.enqueue(type, ids);
            total += ids.size();
            after = ids.isEmpty() ? after : ids.get(ids.size() - 1);
        } while (ids.size() == PAGE_SIZE);
        return total;
    }
}
