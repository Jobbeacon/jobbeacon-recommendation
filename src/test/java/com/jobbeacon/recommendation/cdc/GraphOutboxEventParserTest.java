package com.jobbeacon.recommendation.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobbeacon.recommendation.queue.GraphEntityType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GraphOutboxEventParserTest {

  private static final UUID ENTITY = UUID.randomUUID();

  private final GraphOutboxEventParser parser = new GraphOutboxEventParser(new ObjectMapper());

  @Test
  void insertedRowBecomesAChange() {
    assertThat(parser.parse(event("c", "graph_sync_outbox", "CANDIDATE")))
        .contains(new GraphOutboxChange(GraphEntityType.CANDIDATE, ENTITY));
  }

  @Test
  void snapshotReadBecomesAChange() {
    assertThat(parser.parse(event("r", "graph_sync_outbox", "JOB")))
        .contains(new GraphOutboxChange(GraphEntityType.JOB, ENTITY));
  }

  @Test
  void schemaEnvelopeIsUnwrapped() {
    String wrapped = "{\"schema\": {}, \"payload\": " + event("c", "graph_sync_outbox", "JOB") + "}";

    assertThat(parser.parse(wrapped)).contains(new GraphOutboxChange(GraphEntityType.JOB, ENTITY));
  }

  @Test
  void heartbeatsUpdatesAndDeletesAreIgnored() {
    assertThat(parser.parse(event("u", "debezium_heartbeat", "JOB"))).isEmpty();
    assertThat(parser.parse(event("c", "debezium_heartbeat", "JOB"))).isEmpty();
    assertThat(parser.parse(event("u", "graph_sync_outbox", "JOB"))).isEmpty();
    assertThat(parser.parse(event("d", "graph_sync_outbox", "JOB"))).isEmpty();
    assertThat(parser.parse(null)).isEmpty();
  }

  @Test
  void unknownEntityTypeIsSkippedInsteadOfStoppingTheStream() {
    assertThat(parser.parse(event("c", "graph_sync_outbox", "COMPANY"))).isEmpty();
  }

  private static String event(String op, String table, String entityType) {
    return """
        {"op": "%s", "source": {"table": "%s"},
         "after": {"id": "%s", "entity_type": "%s", "entity_id": "%s", "created_at": "2026-11-08T00:00:00Z"}}
        """.formatted(op, table, UUID.randomUUID(), entityType, ENTITY);
  }
}
