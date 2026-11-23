package com.jobbeacon.recommendation.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jobbeacon.recommendation.graph.GraphRefreshWorker;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Streams the outbox with the real Debezium engine from a Postgres with logical replication. Nothing else runs the
 * engine, so without this a Postgres driver Debezium cannot work with goes unnoticed until it is deployed.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"app.cdc.enabled=true", "eureka.client.enabled=false"})
@Testcontainers
// Stops the engine before the container goes away.
@DirtiesContext
class GraphOutboxCdcIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
      .withCommand("postgres", "-c", "wal_level=logical");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) throws Exception {
    // The backend's side. One database plays both parts here.
    try (Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("create table graph_sync_outbox (id uuid primary key, entity_type varchar(30) not null, "
          + "entity_id uuid not null, created_at timestamptz not null default now())");
      statement.execute("create table debezium_heartbeat (id smallint primary key, ts timestamptz not null)");
    }
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("app.cdc.source-url", POSTGRES::getJdbcUrl);
    registry.add("app.cdc.source-username", POSTGRES::getUsername);
    registry.add("app.cdc.source-password", POSTGRES::getPassword);
  }

  @Autowired
  private JdbcTemplate jdbc;
  @Autowired
  private TestRestTemplate rest;
  /** Would drain the queue into a Neo4j that isn't there. */
  @MockBean
  private GraphRefreshWorker worker;

  @Test
  void outboxRowsReachTheQueueAndTheStreamCountsAsHealthy() {
    // Waits for the stream itself first: rows written earlier would arrive through the initial snapshot, which works
    // even when streaming does not.
    await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).until(() -> jdbc.queryForObject(
        "select count(*) from pg_replication_slots where active", Integer.class) == 1);

    UUID job = UUID.randomUUID();
    jdbc.update("insert into graph_sync_outbox (id, entity_type, entity_id) values (?, 'JOB', ?)", UUID.randomUUID(), job);

    await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(
        jdbc.queryForList("select entity_type from graph_sync_queue where entity_id = ?", String.class, job))
        .isEqualTo(List.of("JOB")));
    assertThat(rest.getForEntity("/api/v1/health", String.class).getStatusCode().value()).isEqualTo(200);
  }
}
