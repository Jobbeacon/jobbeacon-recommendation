package com.jobbeacon.recommendation.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Records that entities must be re-synced to the graph. When the change happens in this service (an interaction), call
 * it inside the same transaction, so the queue row commits or rolls back together with the change.
 */
@Service
@RequiredArgsConstructor
public class GraphSyncQueueService {

    private final GraphSyncQueueRepository queueRepository;

    public void candidateChanged(UUID userId) {
        enqueue(GraphEntityType.CANDIDATE, List.of(userId));
    }

    @Transactional
    public void enqueue(GraphEntityType type, Collection<UUID> entityIds) {
        if (entityIds.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        queueRepository.saveAll(entityIds.stream().map(entityId -> {
            GraphSyncQueueItem item = new GraphSyncQueueItem();
            item.setId(UUID.randomUUID());
            item.setEntityType(type);
            item.setEntityId(entityId);
            item.setCreatedAt(now);
            return item;
        }).toList());
    }
}
