package com.jobbeacon.recommendation.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobbeacon.recommendation.backend.BackendClient;
import com.jobbeacon.recommendation.backend.dto.BackendCandidates;
import com.jobbeacon.recommendation.backend.dto.BackendJob;
import com.jobbeacon.recommendation.config.GraphProperties;
import com.jobbeacon.recommendation.interaction.JobInteractionType;
import com.jobbeacon.recommendation.interaction.UserJobInteraction;
import com.jobbeacon.recommendation.interaction.UserJobInteractionRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** Runs the real Cypher against a throwaway Neo4j; the backend and the interaction table are mocked. */
class GraphSyncServiceCandidateIT {

  private static final String PASSWORD = "test-password";
  private static final UUID USER = UUID.randomUUID();
  private static final UUID SKILL = UUID.randomUUID();
  private static final UUID JOB = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");

  private static final GenericContainer<?> NEO4J = new GenericContainer<>("neo4j:5.26")
      .withEnv("NEO4J_AUTH", "neo4j/" + PASSWORD)
      .withExposedPorts(7687)
      .waitingFor(Wait.forLogMessage(".*Started.*", 1).withStartupTimeout(Duration.ofMinutes(3)));

  private static Driver driver;

  private final BackendClient backendClient = mock(BackendClient.class);
  private final UserJobInteractionRepository interactionRepository = mock(UserJobInteractionRepository.class);
  private GraphSyncService service;

  @BeforeAll
  static void startNeo4j() {
    NEO4J.start();
    driver = GraphDatabase.driver(
        "bolt://" + NEO4J.getHost() + ":" + NEO4J.getMappedPort(7687),
        AuthTokens.basic("neo4j", PASSWORD));
    driver.verifyConnectivity();
  }

  @AfterAll
  static void stopNeo4j() {
    driver.close();
    NEO4J.stop();
  }

  @BeforeEach
  void setUp() {
    run("MATCH (n) DETACH DELETE n");
    service = new GraphSyncService(driver, backendClient, interactionRepository,
        new GraphProperties("neo4j", 100, 10, Duration.ofDays(180), true));
    when(backendClient.findJobs(any())).thenReturn(List.of(
        new BackendJob(JOB, "Backend", null, null, NOW, null, List.of(), List.of())));
    givenCandidateData(
        List.of(USER),
        List.of(new BackendCandidates.Skill(USER, SKILL, "Java", "java", new BigDecimal("0.900"), 24,
            LocalDate.of(2025, 6, 1))),
        List.of(new BackendCandidates.SavedJob(USER, JOB, NOW)),
        List.of(interaction(JobInteractionType.VIEWED, 3)));
  }

  @Test
  void createsCandidateWithAllRelationshipsAndTheJobItPointsTo() {
    service.syncCandidates(Set.of(USER));

    assertThat(count("MATCH (:Candidate {id: $id})-[:HAS_SKILL {confidence: 0.9, experience_months: 24, last_used_at: date('2025-06-01')}]->(:Skill) RETURN count(*)")).isOne();
    assertThat(count("MATCH (:Candidate {id: $id})-[:SAVED {count: 1}]->(:Job {title: 'Backend'}) RETURN count(*)")).isOne();
    assertThat(count("MATCH (:Candidate {id: $id})-[:VIEWED {count: 3}]->(:Job) RETURN count(*)")).isOne();
  }

  @Test
  void hasDataOnlyOnceTheGraphHoldsNodes() {
    run("CREATE CONSTRAINT probe_id IF NOT EXISTS FOR (n:Probe) REQUIRE n.id IS UNIQUE");
    try {
      assertThat(service.hasData()).isFalse();

      service.syncCandidates(Set.of(USER));

      assertThat(service.hasData()).isTrue();
    } finally {
      run("DROP CONSTRAINT probe_id IF EXISTS");
    }
  }

  @Test
  void syncingTwiceCreatesNothingNew() {
    service.syncCandidates(Set.of(USER));
    service.syncCandidates(Set.of(USER));

    assertThat(count("MATCH (:Candidate {id: $id})-[r]->() RETURN count(r)")).isEqualTo(3);
    assertThat(count("MATCH (c:Candidate) RETURN count(c)")).isOne();
  }

  @Test
  void removesRelationshipsThatNoLongerExist() {
    service.syncCandidates(Set.of(USER));
    givenCandidateData(List.of(USER), List.of(), List.of(), List.of());

    service.syncCandidates(Set.of(USER));

    assertThat(count("MATCH (:Candidate {id: $id})-[r]->() RETURN count(r)")).isZero();
    assertThat(count("MATCH (j:Job) RETURN count(j)")).isOne();
  }

  @Test
  void removesCandidateWhenTheBackendNoLongerHasTheUser() {
    service.syncCandidates(Set.of(USER));
    givenCandidateData(List.of(), List.of(), List.of(), List.of(interaction(JobInteractionType.VIEWED, 1)));

    service.syncCandidates(Set.of(USER));

    assertThat(count("MATCH (c:Candidate) RETURN count(c)")).isZero();
  }

  @Test
  void syncsJobsFromTheBackendAndRemovesThoseItNoLongerReturns() {
    UUID company = UUID.randomUUID();
    when(backendClient.findJobs(any())).thenReturn(List.of(new BackendJob(JOB, "Backend", company, "Acme  Corp", NOW,
        null, List.of(new BackendJob.Skill(SKILL, "Java", "java")),
        List.of(new BackendJob.Source("itviec.com", "https://itviec.com/1")))));

    service.syncJobs(Set.of(JOB));

    assertThat(count("MATCH (:Job {title: 'Backend'})-[:REQUIRES_SKILL]->(:Skill {name: 'Java'}) RETURN count(*)")).isOne();
    assertThat(count("MATCH (:Job)-[:POSTED_BY]->(:Company {normalized_name: 'acme corp'}) RETURN count(*)")).isOne();

    when(backendClient.findJobs(any())).thenReturn(List.of());
    service.syncJobs(Set.of(JOB));

    assertThat(count("MATCH (j:Job) RETURN count(j)")).isZero();
  }

  @Test
  void largeJobSyncsAreSplitIntoRequestsTheBackendAccepts() {
    List<UUID> ids = java.util.stream.IntStream.range(0, GraphSyncService.MAX_IDS_PER_REQUEST + 1)
        .mapToObj(i -> UUID.randomUUID()).toList();
    when(backendClient.findJobs(any())).thenReturn(List.of());

    service.syncJobs(ids);

    org.mockito.Mockito.verify(backendClient, org.mockito.Mockito.times(2)).findJobs(org.mockito.ArgumentMatchers.argThat(
        request -> request.ids().size() <= GraphSyncService.MAX_IDS_PER_REQUEST));
  }

  private void givenCandidateData(
      List<UUID> existingUserIds,
      List<BackendCandidates.Skill> skills,
      List<BackendCandidates.SavedJob> saved,
      List<UserJobInteraction> interactions
  ) {
    when(backendClient.findCandidates(any())).thenReturn(new BackendCandidates(existingUserIds, skills, saved));
    when(interactionRepository.findRecentByUserIds(any(), any())).thenReturn(interactions);
  }

  private static UserJobInteraction interaction(JobInteractionType type, int count) {
    UserJobInteraction interaction = new UserJobInteraction();
    interaction.setId(new UserJobInteraction.UserJobInteractionId(USER, JOB, type));
    interaction.setInteractionCount(count);
    interaction.setFirstAt(NOW.minusSeconds(60));
    interaction.setLastAt(NOW);
    return interaction;
  }

  private static void run(String cypher) {
    try (Session session = driver.session()) {
      session.run(cypher).consume();
    }
  }

  private static long count(String cypher) {
    try (Session session = driver.session()) {
      return session.run(cypher, Map.of("id", USER.toString())).single().get(0).asLong();
    }
  }
}
