package com.jobbeacon.recommendation.graph;

import com.jobbeacon.recommendation.config.GraphProperties;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps the in-memory GDS graph that recommendations run on. Each rebuild creates a new graph and only then replaces
 * the active one, so a failed rebuild leaves the previous graph usable.
 *
 * <p>Relationships are undirected because Candidate and Job both point at Skill: followed by direction, a walk from a
 * candidate would stop at the skill and never reach a job. Expired jobs and interactions older than
 * {@code app.graph.interaction-window} are left out here, because neither produces a write that would remove them from
 * Neo4j in time.
 */
@Slf4j
@Service
public class GraphProjectionService {

    static final String GRAPH_NAME_PREFIX = "jobbeacon-recommendation-";

    // Initial values, to be tuned against recommendation quality. HAS_SKILL is further scaled by the CV confidence, and
    // interactions by how often they were repeated (see MAX_REPEAT_FACTOR).
    private static final Map<String, Double> RELATIONSHIP_WEIGHTS = Map.of(
        "HAS_SKILL", 3.0,
        "REQUIRES_SKILL", 1.0,
        "APPLY_CLICKED", 5.0,
        "SAVED", 4.0,
        "VIEWED", 1.0
    );

    /**
     * Repeats count as {@code 1 + ln(count)}: each one adds less than the last, and the factor stops at this value
     * (reached around 7 repeats) so that viewing a job many times never outweighs saving or applying to it. Repeats
     * within 30 minutes are not counted at all (see {@code UserJobInteractionRepository#record}).
     */
    static final double MAX_REPEAT_FACTOR = 3.0;

    private static final String PROJECT = """
        MATCH (source)-[r:HAS_SKILL|REQUIRES_SKILL|APPLY_CLICKED|SAVED|VIEWED]->(target)
        WHERE (source.expires_at IS NULL OR source.expires_at > $now)
          AND (target.expires_at IS NULL OR target.expires_at > $now)
          AND (NOT type(r) IN ['APPLY_CLICKED', 'VIEWED'] OR r.last_at >= $interactionsSince)
        WITH source, r, target, 1.0 + log(coalesce(r.count, 1)) AS repeatFactor
        WITH source, r, target,
             CASE WHEN repeatFactor > $maxRepeatFactor THEN $maxRepeatFactor ELSE repeatFactor END AS repeatFactor
        WITH gds.graph.project(
            $graphName,
            source,
            target,
            {
                sourceNodeLabels: labels(source),
                targetNodeLabels: labels(target),
                relationshipType: type(r),
                relationshipProperties: {weight: $weights[type(r)] * coalesce(r.confidence, 1.0) * repeatFactor}
            },
            {undirectedRelationshipTypes: ['*']}
        ) AS graph
        RETURN graph.nodeCount AS nodes, graph.relationshipCount AS relationships
        """;

    private static final String LIST_GRAPHS = """
        CALL gds.graph.list()
        YIELD graphName
        WHERE graphName STARTS WITH $prefix
        RETURN graphName
        """;

    private static final String DROP_GRAPH = """
        CALL gds.graph.drop($graphName, false)
        YIELD graphName
        RETURN graphName
        """;

    private final Driver driver;
    private final SessionConfig sessionConfig;
    private final Duration interactionWindow;
    private volatile String activeGraphName;

    public GraphProjectionService(Driver driver, GraphProperties properties) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.forDatabase(properties.database());
        this.interactionWindow = properties.interactionWindow();
    }

    /** Empty until the first successful rebuild, or while the graph has nothing to project. */
    public Optional<String> activeGraphName() {
        return Optional.ofNullable(activeGraphName);
    }

    public void rebuild() {
        String graphName = GRAPH_NAME_PREFIX + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        try (Session session = driver.session(sessionConfig)) {
            Record result = session.run(PROJECT, Map.of(
                "graphName", graphName,
                "now", now,
                "interactionsSince", now.minus(interactionWindow),
                "weights", RELATIONSHIP_WEIGHTS,
                "maxRepeatFactor", MAX_REPEAT_FACTOR
            )).single();

            boolean projected = !result.get("nodes").isNull();
            activeGraphName = projected ? graphName : null;
            dropAllExcept(session, activeGraphName);
            if (projected) {
                log.info("Rebuilt graph projection {} with {} nodes and {} relationships", graphName,
                    result.get("nodes").asLong(), result.get("relationships").asLong());
            } else {
                log.info("Graph has nothing to project yet");
            }
        }
    }

    // Lists from Neo4j rather than tracking names here, so graphs left by a previous backend process are removed too.
    private void dropAllExcept(Session session, String keptGraphName) {
        List<String> graphNames = session.run(LIST_GRAPHS, Map.of("prefix", GRAPH_NAME_PREFIX))
            .list(record -> record.get("graphName").asString());
        for (String graphName : graphNames) {
            if (!graphName.equals(keptGraphName)) {
                session.run(DROP_GRAPH, Map.of("graphName", graphName)).consume();
            }
        }
    }
}
