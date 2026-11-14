package com.jobbeacon.recommendation.graph;

import com.jobbeacon.recommendation.config.GraphProperties;
import com.jobbeacon.recommendation.queue.GraphEntityType;
import com.jobbeacon.recommendation.queue.GraphSyncQueueItem;
import com.jobbeacon.recommendation.queue.GraphSyncQueueRepository;
import feign.FeignException;
import feign.RetryableException;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Refreshes what recommendations read: drains {@code graph_sync_queue} into Neo4j, then rebuilds the projection from
 * the result. Both happen in one run because only the projection is read, so syncing more often than it is rebuilt
 * would make nothing fresher.
 *
 * <p>While Neo4j or the backend is unreachable, rows simply stay pending and the previous projection stays active; a
 * row that fails for any other reason has its attempt count bumped and is retried on later runs until
 * {@code app.graph.max-attempts}.
 */
@Slf4j
@Component
public class GraphRefreshWorker {

    private static final int MAX_ERROR_LENGTH = 2000;
    // Long enough to look back at what was synced recently, short enough that the table stays small.
    static final Duration PROCESSED_RETENTION = Duration.ofDays(7);
    private static final Set<Integer> UNAVAILABLE_STATUSES = Set.of(502, 503, 504);

    private final GraphSyncQueueRepository queueRepository;
    private final GraphSyncService graphSyncService;
    private final GraphProjectionService graphProjectionService;
    private final GraphBackfill graphBackfill;
    private final int batchSize;
    private final int maxAttempts;

    public GraphRefreshWorker(
        GraphSyncQueueRepository queueRepository,
        GraphSyncService graphSyncService,
        GraphProjectionService graphProjectionService,
        GraphBackfill graphBackfill,
        GraphProperties properties
    ) {
        this.queueRepository = queueRepository;
        this.graphSyncService = graphSyncService;
        this.graphProjectionService = graphProjectionService;
        this.graphBackfill = graphBackfill;
        this.batchSize = properties.batchSize();
        this.maxAttempts = properties.maxAttempts();
    }

    // Fixed delay (not rate) so a slow run never overlaps the next one. The short initial delay is there because the
    // service has no active projection after a restart.
    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "${app.graph.refresh-interval}")
    public void refresh() {
        // Part of the run rather than a startup step, so a backfill that fails (backend down) is retried next run.
        if (!backfill()) {
            return;
        }
        // Jobs first, so a candidate's relationships find their job nodes without syncing them one by one.
        boolean reachable = drain(GraphEntityType.JOB, graphSyncService::syncJobs)
            && drain(GraphEntityType.CANDIDATE, graphSyncService::syncCandidates);
        if (!reachable) {
            return;
        }
        try {
            graphProjectionService.rebuild();
        } catch (Exception e) {
            log.warn("Graph projection rebuild failed, keeping the previous one: {}", e.getMessage(), e);
        }
        purgeProcessed();
    }

    private void purgeProcessed() {
        try {
            int deleted = queueRepository.deleteProcessedBefore(Instant.now().minus(PROCESSED_RETENTION));
            if (deleted > 0) {
                log.info("Deleted {} processed graph sync queue rows", deleted);
            }
        } catch (Exception e) {
            log.warn("Could not delete processed graph sync queue rows: {}", e.getMessage());
        }
    }

    private boolean backfill() {
        try {
            graphBackfill.runIfNeeded();
            return true;
        } catch (Exception e) {
            log.warn("Graph backfill failed, retrying on the next run: {}", e.getMessage());
            return false;
        }
    }

    /** Returns false when Neo4j or the backend is down and the rest of the backlog has to wait for the next run. */
    private boolean drain(GraphEntityType type, Consumer<Collection<UUID>> sync) {
        List<GraphSyncQueueItem> rows;
        do {
            rows = queueRepository.findPending(type, maxAttempts, PageRequest.of(0, batchSize));
            if (rows.isEmpty()) {
                return true;
            }
            if (!process(type, rows, sync)) {
                return false;
            }
        } while (rows.size() == batchSize);
        return true;
    }

    private boolean process(GraphEntityType type, List<GraphSyncQueueItem> rows, Consumer<Collection<UUID>> sync) {
        // Several rows for the same entity collapse into one sync, since the sync reads current state anyway.
        Map<UUID, List<UUID>> rowIdsByEntity = new LinkedHashMap<>();
        for (GraphSyncQueueItem row : rows) {
            rowIdsByEntity.computeIfAbsent(row.getEntityId(), id -> new ArrayList<>()).add(row.getId());
        }

        try {
            sync.accept(rowIdsByEntity.keySet());
            queueRepository.markProcessed(allRowIds(rowIdsByEntity.values()), Instant.now());
            log.info("Synced {} {} entities to graph from {} queue rows", rowIdsByEntity.size(), type, rows.size());
            return true;
        } catch (Exception e) {
            if (isUnavailable(e)) {
                // Not the rows' fault, so attempts are left untouched and the rows wait however long the outage
                // lasts. Retrying one by one would only fail slower.
                log.warn("Neo4j or backend unavailable, {} {} queue rows stay pending: {}", rows.size(), type,
                    e.getMessage());
                return false;
            }
            log.warn("Graph sync batch of {} failed, retrying one by one: {}", type, e.getMessage(), e);
            rowIdsByEntity.forEach((entityId, rowIds) -> processSingle(type, entityId, rowIds, sync));
            return true;
        }
    }

    // Isolates a bad entity so it cannot keep the rest of its batch from syncing.
    private void processSingle(GraphEntityType type, UUID entityId, List<UUID> rowIds, Consumer<Collection<UUID>> sync) {
        try {
            sync.accept(Set.of(entityId));
            queueRepository.markProcessed(rowIds, Instant.now());
        } catch (Exception e) {
            log.warn("Graph sync failed for {} {}: {}", type, entityId, e.getMessage());
            queueRepository.recordFailure(rowIds, truncate(e.getMessage()));
        }
    }

    // Connection failures and gateway errors say nothing about the data; anything else (e.g. a 500 caused by one bad
    // entity) is treated as the batch's fault so it can be isolated.
    static boolean isUnavailable(Exception e) {
        return e instanceof ServiceUnavailableException
            || e instanceof RetryableException
            || (e instanceof FeignException feign && UNAVAILABLE_STATUSES.contains(feign.status()));
    }

    private static List<UUID> allRowIds(Collection<List<UUID>> groups) {
        return groups.stream().flatMap(List::stream).toList();
    }

    private static String truncate(String message) {
        String text = String.valueOf(message);
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
