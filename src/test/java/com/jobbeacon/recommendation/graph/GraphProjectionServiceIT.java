package com.jobbeacon.recommendation.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.jobbeacon.recommendation.config.GraphProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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

/** Runs the projection and real Personalized PageRank on a throwaway Neo4j with the same GDS plugin as deployment. */
class GraphProjectionServiceIT {

  private static final String PASSWORD = "test-password";

  private static final GenericContainer<?> NEO4J = new GenericContainer<>("neo4j:5.26")
      .withEnv("NEO4J_AUTH", "neo4j/" + PASSWORD)
      .withEnv("NEO4J_PLUGINS", "[\"graph-data-science\"]")
      .withEnv("NEO4J_dbms_security_procedures_unrestricted", "gds.*")
      .withExposedPorts(7687)
      .waitingFor(Wait.forLogMessage(".*Started.*", 1).withStartupTimeout(Duration.ofMinutes(5)));

  private static Driver driver;

  private GraphProjectionService service;

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
    run("CALL gds.graph.list() YIELD graphName CALL gds.graph.drop(graphName) YIELD nodeCount RETURN count(*)");
    service = new GraphProjectionService(driver, new GraphProperties("neo4j", 100, 10, Duration.ofDays(180), true));
  }

  @Test
  void candidateReachesAJobThroughASharedSkill() {
    run("""
        CREATE (c:Candidate {id: 'candidate'})-[:HAS_SKILL {confidence: 0.9}]->(s:Skill {id: 'java'}),
               (:Job {id: 'match'})-[:REQUIRES_SKILL]->(s),
               (:Job {id: 'other'})-[:REQUIRES_SKILL]->(:Skill {id: 'cooking'})
        """);

    service.rebuild();

    assertThat(recommendedJobs("candidate")).contains("match").doesNotContain("other");
  }

  @Test
  void strongerInteractionsRankHigher() {
    run("""
        CREATE (c:Candidate {id: 'candidate'}),
               (c)-[:VIEWED {last_at: datetime()}]->(:Job {id: 'viewed'}),
               (c)-[:APPLY_CLICKED {last_at: datetime()}]->(:Job {id: 'applied'})
        """);

    service.rebuild();

    assertThat(recommendedJobs("candidate")).containsExactly("applied", "viewed");
  }

  @Test
  void repeatedInteractionsWeighMore() {
    run("""
        CREATE (c:Candidate {id: 'candidate'}),
               (c)-[:VIEWED {count: 1, last_at: datetime()}]->(:Job {id: 'once'}),
               (c)-[:VIEWED {count: 5, last_at: datetime()}]->(:Job {id: 'often'})
        """);

    service.rebuild();

    assertThat(recommendedJobs("candidate")).containsExactly("often", "once");
    assertThat(weight("once")).isEqualTo(1.0);
    assertThat(weight("often")).isCloseTo(1.0 + Math.log(5), within(1e-9));
  }

  @Test
  void repeatsAreCappedSoViewsNeverOutweighASave() {
    run("""
        CREATE (c:Candidate {id: 'candidate'}),
               (c)-[:VIEWED {count: 1000, last_at: datetime()}]->(:Job {id: 'viewedALot'}),
               (c)-[:SAVED {count: 1, last_at: datetime()}]->(:Job {id: 'saved'})
        """);

    service.rebuild();

    assertThat(weight("viewedALot")).isEqualTo(GraphProjectionService.MAX_REPEAT_FACTOR);
    assertThat(recommendedJobs("candidate")).containsExactly("saved", "viewedALot");
  }

  @Test
  void expiredJobsAreLeftOut() {
    run("""
        CREATE (c:Candidate {id: 'candidate'})-[:HAS_SKILL]->(s:Skill {id: 'java'}),
               (:Job {id: 'open', expires_at: datetime() + duration('P1D')})-[:REQUIRES_SKILL]->(s),
               (:Job {id: 'noExpiry'})-[:REQUIRES_SKILL]->(s),
               (:Job {id: 'expired', expires_at: datetime() - duration('P1D')})-[:REQUIRES_SKILL]->(s)
        """);

    service.rebuild();

    assertThat(recommendedJobs("candidate")).containsExactlyInAnyOrder("open", "noExpiry");
  }

  @Test
  void onlyOldInteractionsAreLeftOutNotOldSaves() {
    run("""
        CREATE (c:Candidate {id: 'candidate'}),
               (c)-[:VIEWED {last_at: datetime() - duration('P200D')}]->(:Job {id: 'oldView'}),
               (c)-[:VIEWED {last_at: datetime()}]->(:Job {id: 'recentView'}),
               (c)-[:SAVED {last_at: datetime() - duration('P200D')}]->(:Job {id: 'oldSave'})
        """);

    service.rebuild();

    assertThat(recommendedJobs("candidate")).containsExactlyInAnyOrder("recentView", "oldSave");
  }

  @Test
  void rebuildReplacesThePreviousGraph() {
    run("CREATE (:Candidate {id: 'candidate'})-[:HAS_SKILL]->(:Skill {id: 'java'})");
    service.rebuild();
    String first = service.activeGraphName().orElseThrow();

    service.rebuild();

    String second = service.activeGraphName().orElseThrow();
    assertThat(second).isNotEqualTo(first);
    assertThat(graphNames()).containsExactly(second);
  }

  @Test
  void graphsLeftByAPreviousProcessAreDropped() {
    run("CREATE (:Candidate {id: 'candidate'})-[:HAS_SKILL]->(:Skill {id: 'java'})");
    service.rebuild();
    String leftover = service.activeGraphName().orElseThrow();
    GraphProjectionService restarted =
        new GraphProjectionService(driver, new GraphProperties("neo4j", 100, 10, Duration.ofDays(180), true));

    restarted.rebuild();

    assertThat(graphNames()).doesNotContain(leftover).containsExactly(restarted.activeGraphName().orElseThrow());
  }

  @Test
  void emptyGraphHasNoActiveProjection() {
    service.rebuild();

    assertThat(service.activeGraphName()).isEmpty();
    assertThat(graphNames()).isEmpty();
  }

  @Test
  void graphThatBecameEmptyDropsTheProjection() {
    run("CREATE (:Candidate {id: 'candidate'})-[:HAS_SKILL]->(:Skill {id: 'java'})");
    service.rebuild();
    run("MATCH (n) DETACH DELETE n");

    service.rebuild();

    assertThat(service.activeGraphName()).isEmpty();
    assertThat(graphNames()).isEmpty();
  }

  private List<String> recommendedJobs(String candidateId) {
    try (Session session = driver.session()) {
      return session.run("""
          MATCH (c:Candidate {id: $candidateId})
          CALL gds.pageRank.stream($graphName, {
              sourceNodes: [c],
              relationshipWeightProperty: 'weight'
          })
          YIELD nodeId, score
          WITH gds.util.asNode(nodeId) AS node, score
          WHERE node:Job AND score > 0
          RETURN node.id AS id
          ORDER BY score DESC
          """, Map.of("candidateId", candidateId, "graphName", service.activeGraphName().orElseThrow()))
          .list(record -> record.get("id").asString());
    }
  }

  /** Weight of the projected relationship between the candidate and the given job. */
  private double weight(String jobId) {
    try (Session session = driver.session()) {
      return session.run("""
          CALL gds.graph.relationshipProperty.stream($graphName, 'weight')
          YIELD sourceNodeId, targetNodeId, propertyValue
          WITH gds.util.asNode(sourceNodeId) AS source, gds.util.asNode(targetNodeId) AS target, propertyValue
          WHERE source:Candidate AND target.id = $jobId
          RETURN propertyValue
          """, Map.of("graphName", service.activeGraphName().orElseThrow(), "jobId", jobId))
          .single().get(0).asDouble();
    }
  }

  private static List<String> graphNames() {
    try (Session session = driver.session()) {
      return session.run("CALL gds.graph.list() YIELD graphName RETURN graphName")
          .list(record -> record.get("graphName").asString());
    }
  }

  private static void run(String cypher) {
    try (Session session = driver.session()) {
      session.run(cypher).consume();
    }
  }
}
