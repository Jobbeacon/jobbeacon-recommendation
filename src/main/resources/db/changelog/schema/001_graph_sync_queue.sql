-- Entities waiting to be pushed to the Neo4j graph. Filled from the backend's graph_sync_outbox (streamed by CDC),
-- by recorded interactions (in the same transaction) and by the backfill. The sync re-reads each entity's current
-- state, so a duplicate or retried row is harmless.
CREATE TABLE IF NOT EXISTS graph_sync_queue (
    id            UUID        NOT NULL,
    entity_type   VARCHAR(30) NOT NULL,
    entity_id     UUID        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at  TIMESTAMPTZ,
    attempts      INT         NOT NULL DEFAULT 0,
    last_error    TEXT,

    CONSTRAINT pk_graph_sync_queue PRIMARY KEY (id),
    CONSTRAINT ck_graph_sync_queue_entity_type CHECK (entity_type IN ('JOB', 'CANDIDATE'))
);

CREATE INDEX IF NOT EXISTS idx_graph_sync_queue_pending
    ON graph_sync_queue (entity_type, created_at)
    WHERE processed_at IS NULL;
