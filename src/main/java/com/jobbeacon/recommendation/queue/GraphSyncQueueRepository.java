package com.jobbeacon.recommendation.queue;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface GraphSyncQueueRepository extends JpaRepository<GraphSyncQueueItem, UUID> {

    @Query("""
            select q
            from GraphSyncQueueItem q
            where q.entityType = :entityType and q.processedAt is null and q.attempts < :maxAttempts
            order by q.createdAt asc
            """)
    List<GraphSyncQueueItem> findPending(
        @Param("entityType") GraphEntityType entityType,
        @Param("maxAttempts") int maxAttempts,
        Pageable pageable
    );

    @Transactional
    @Modifying
    @Query("update GraphSyncQueueItem q set q.processedAt = :processedAt where q.id in :ids")
    int markProcessed(@Param("ids") Collection<UUID> ids, @Param("processedAt") Instant processedAt);

    @Transactional
    @Modifying
    @Query("update GraphSyncQueueItem q set q.attempts = q.attempts + 1, q.lastError = :error where q.id in :ids")
    int recordFailure(@Param("ids") Collection<UUID> ids, @Param("error") String error);
}
