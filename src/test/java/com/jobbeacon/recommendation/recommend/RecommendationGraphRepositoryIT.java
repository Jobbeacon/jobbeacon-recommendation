package com.jobbeacon.recommendation.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobbeacon.recommendation.config.GraphProperties;
import com.jobbeacon.recommendation.config.RecommendationProperties;
import com.jobbeacon.recommendation.graph.GraphProjectionService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.exceptions.ClientException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** The recommendation queries on a throwaway Neo4j with the same GDS plugin as deployment. */
class RecommendationGraphRepositoryIT {

  private static final String PASSWORD = "test-password";

  private static final GenericContainer<?> NEO4J = new GenericContainer<>("neo4j:5.26")
      .withEnv("NEO4J_AUTH", "neo4j/" + PASSWORD)
      .withEnv("NEO4J_PLUGINS", "[\"graph-data-science\"]")
      .withEnv("NEO4J_dbms_security_procedures_unrestricted", "gds.*")
      .withExposedPorts(7687)
      .waitingFor(Wait.forLogMessage(".*Started.*", 1).withStartupTimeout(Duration.ofMinutes(5)));

  private static Driver driver;

  private final UUID candidate = UUID.randomUUID();
  private final UUID match = UUID.randomUUID();
  private final UUID saved = UUID.randomUUID();
  private final UUID applied = UUID.randomUUID();
  private final UUID viewed = UUID.randomUUID();
  private final UUID unrelated = UUID.randomUUID();
  private final UUID likeViewed = UUID.randomUUID();

  private GraphProjectionService projection;
  private RecommendationGraphRepository repository;

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
    run("MATCH (n) DETACH DELETE n", Map.of());
    run("CALL gds.graph.list() YIELD graphName CALL gds.graph.drop(graphName) YIELD nodeCount RETURN count(*)", Map.of());
    GraphProperties graphProperties = new GraphProperties("neo4j", 100, 10, Duration.ofDays(180), true);
    projection = new GraphProjectionService(driver, graphProperties);
    repository = new RecommendationGraphRepository(driver, graphProperties,
        new RecommendationProperties(100, 50, 0.5, Duration.ofMinutes(15), Duration.ofMinutes(5),
            Duration.ofDays(14), 20, 0.85));
  }

  @Test
  void ranksJobsNearTheCandidateAndLeavesOutThoseTheyKnow() {
    run("""
        CREATE (c:Candidate {id: $candidate})-[:HAS_SKILL {confidence: 0.9}]->(java:Skill {id: 'java'}),
               (:Job {id: $match})-[:REQUIRES_SKILL]->(java),
               (c)-[:SAVED {last_at: datetime()}]->(:Job {id: $saved})-[:REQUIRES_SKILL]->(java),
               (c)-[:APPLY_CLICKED {last_at: datetime()}]->(:Job {id: $applied}),
               (c)-[:VIEWED {last_at: datetime()}]->(:Job {id: $viewed})-[:REQUIRES_SKILL]->(sql:Skill {id: 'sql'}),
               (:Job {id: $likeViewed})-[:REQUIRES_SKILL]->(sql),
               (:Job {id: $unrelated})-[:REQUIRES_SKILL]->(:Skill {id: 'cooking'})
        """, ids());
    projection.rebuild();

    List<ScoredJob> ranking = repository.personalizedRanking(graphName(), candidate, 10);

    assertThat(ranking).extracting(ScoredJob::jobId).containsExactlyInAnyOrder(match, likeViewed);
    assertThat(ranking).isSortedAccordingTo((a, b) -> Double.compare(b.score(), a.score()));
  }

  @Test
  void limitKeepsTheBestJobs() {
    run("""
        CREATE (c:Candidate {id: $candidate}),
               (c)-[:APPLY_CLICKED {last_at: datetime()}]->(a:Job {id: $applied}),
               (a)-[:REQUIRES_SKILL]->(s:Skill {id: 'java'}),
               (:Job {id: $match})-[:REQUIRES_SKILL]->(s),
               (:Job {id: $likeViewed})-[:REQUIRES_SKILL]->(s)
        """, ids());
    projection.rebuild();

    assertThat(repository.personalizedRanking(graphName(), candidate, 10)).hasSize(2);
    assertThat(repository.personalizedRanking(graphName(), candidate, 1)).hasSize(1);
  }

  @Test
  void unknownCandidateHasNoRanking() {
    run("CREATE (:Candidate {id: $candidate})-[:HAS_SKILL]->(:Skill {id: 'java'})", ids());
    projection.rebuild();

    assertThat(repository.personalizedRanking(graphName(), UUID.randomUUID(), 10)).isEmpty();
  }

  @Test
  void candidateLeftOutOfTheProjectionFails() {
    run("""
        CREATE (:Candidate {id: $candidate})-[:VIEWED {last_at: datetime() - duration('P365D')}]->(:Job {id: $viewed}),
               (:Candidate {id: 'other'})-[:HAS_SKILL]->(:Skill {id: 'java'})
        """, ids());
    projection.rebuild();

    assertThatThrownBy(() -> repository.personalizedRanking(graphName(), candidate, 10))
        .isInstanceOfSatisfying(ClientException.class,
            e -> assertThat(e.code()).isEqualTo(RecommendationService.PROCEDURE_FAILED));
  }

  @Test
  void droppedProjectionFails() {
    run("CREATE (:Candidate {id: $candidate})-[:HAS_SKILL]->(:Skill {id: 'java'})", ids());

    assertThatThrownBy(() -> repository.personalizedRanking("jobbeacon-recommendation-gone", candidate, 10))
        .isInstanceOfSatisfying(ClientException.class,
            e -> assertThat(e.code()).isEqualTo(RecommendationService.PROCEDURE_FAILED));
  }

  @Test
  void matchedSkillsAreTheSurestOnesOfTheCv() {
    run("""
        CREATE (c:Candidate {id: $candidate}),
               (java:Skill {id: 'java', name: 'Java'}), (sql:Skill {id: 'sql', name: 'SQL'}), (go:Skill {id: 'go', name: 'Go'}),
               (c)-[:HAS_SKILL {confidence: 0.5}]->(java), (c)-[:HAS_SKILL {confidence: 0.9}]->(sql), (c)-[:HAS_SKILL]->(go),
               (j:Job {id: $match})-[:REQUIRES_SKILL]->(java), (j)-[:REQUIRES_SKILL]->(sql), (j)-[:REQUIRES_SKILL]->(go),
               (:Job {id: $unrelated})-[:REQUIRES_SKILL]->(:Skill {id: 'cooking', name: 'Cooking'}),
               (:Job {id: $viewed})-[:REQUIRES_SKILL]->(java)
        """, ids());

    assertThat(repository.matchedSkills(candidate, List.of(match, unrelated), 2))
        .containsExactly(Map.entry(match, List.of("SQL", "Java")));
  }

  @Test
  void newestOpenJobsComeFirst() {
    run("""
        CREATE (:Job {id: $match, published_at: datetime() - duration('P1D')}),
               (:Job {id: $viewed, published_at: datetime() - duration('P3D')}),
               (:Job {id: $saved}),
               (:Job {id: $applied, published_at: datetime(), expires_at: datetime() - duration('P1D')})
        """, ids());

    assertThat(repository.newestJobs(10)).containsExactly(match, viewed, saved);
    assertThat(repository.newestJobs(1)).containsExactly(match);
  }

  private String graphName() {
    return projection.activeGraphName().orElseThrow();
  }

  private Map<String, Object> ids() {
    return Map.of(
        "candidate", candidate.toString(),
        "match", match.toString(),
        "saved", saved.toString(),
        "applied", applied.toString(),
        "viewed", viewed.toString(),
        "unrelated", unrelated.toString(),
        "likeViewed", likeViewed.toString());
  }

  private static void run(String cypher, Map<String, Object> parameters) {
    try (Session session = driver.session()) {
      session.run(cypher, parameters).consume();
    }
  }
}
