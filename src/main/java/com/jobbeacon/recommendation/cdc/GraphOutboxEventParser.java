package com.jobbeacon.recommendation.cdc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobbeacon.recommendation.queue.GraphEntityType;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class GraphOutboxEventParser {

    private static final Set<String> APPLIED_OPS = Set.of("c", "r");
    private static final String OUTBOX_TABLE = "graph_sync_outbox";

    private final ObjectMapper objectMapper;

    public GraphOutboxEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<GraphOutboxChange> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(value);
            JsonNode envelope = root.has("schema") && root.path("payload").isObject() ? root.get("payload") : root;
            if (!APPLIED_OPS.contains(envelope.path("op").asText())) {
                return Optional.empty();
            }
            // Heartbeat rows and anything else in the publication are not changes.
            if (!OUTBOX_TABLE.equals(envelope.path("source").path("table").asText())) {
                return Optional.empty();
            }
            JsonNode after = envelope.path("after");
            GraphEntityType type = parseType(after.path("entity_type").asText(null));
            String entityId = after.path("entity_id").asText(null);
            // Skipped rather than failed: a row this version cannot handle (e.g. a type added to the backend later)
            // would otherwise stop the stream for good, since the same event is redelivered after every restart.
            if (type == null || entityId == null) {
                return Optional.empty();
            }
            return Optional.of(new GraphOutboxChange(type, UUID.fromString(entityId)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse graph outbox event", e);
        }
    }

    private static GraphEntityType parseType(String value) {
        if (value == null) {
            return null;
        }
        try {
            return GraphEntityType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
