package com.jobbeacon.recommendation.recommend;

import com.jobbeacon.recommendation.config.GraphProperties;
import com.jobbeacon.recommendation.config.RecommendationProperties;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** The Neo4j reads behind recommendations. Node ids in the graph are the backend's UUIDs as strings. */
@Repository
public class RecommendationGraphRepository {

    /**
     * Personalized PageRank from the candidate: walks restart at the candidate, so jobs close to their skills, saves
     * and interactions score highest. Walks pass through the jobs the candidate saved, applied to or viewed, which is
     * how similar jobs are found, but those jobs themselves are left out: being one step away they would always win,
     * and the candidate knows them already. Jobs that expired after the projection was built are left out too.
     */
    private static final String PERSONALIZED_PAGE_RANK = """
        MATCH (c:Candidate {id: $candidateId})
        CALL gds.pageRank.stream($graphName, {
            sourceNodes: [c],
            relationshipWeightProperty: 'weight',
            maxIterations: $maxIterations,
            dampingFactor: $dampingFactor
        })
        YIELD nodeId, score
        WITH c, gds.util.asNode(nodeId) AS job, score
        WHERE job:Job AND score > 0
          AND (job.expires_at IS NULL OR job.expires_at > $now)
          AND NOT (c)-[:SAVED|APPLY_CLICKED|VIEWED]->(job)
        RETURN job.id AS id, score
        ORDER BY score DESC, id
        LIMIT $limit
        """;

    private static final String MATCHED_SKILLS = """
        MATCH (:Candidate {id: $candidateId})-[has:HAS_SKILL]->(skill:Skill)<-[:REQUIRES_SKILL]-(job:Job)
        WHERE job.id IN $jobIds
        WITH job, skill, coalesce(has.confidence, 0.0) AS confidence
        ORDER BY confidence DESC, skill.name
        RETURN job.id AS id, collect(skill.name)[0..$limit] AS skills
        """;

    private static final String NEWEST_JOBS = """
        MATCH (job:Job)
        WHERE job.expires_at IS NULL OR job.expires_at > $now
        RETURN job.id AS id
        ORDER BY job.published_at IS NULL, job.published_at DESC, id
        LIMIT $limit
        """;

    private final Driver driver;
    private final SessionConfig sessionConfig;
    private final int maxIterations;
    private final double dampingFactor;

    public RecommendationGraphRepository(
        Driver driver,
        GraphProperties graphProperties,
        RecommendationProperties recommendationProperties
    ) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.forDatabase(graphProperties.database());
        this.maxIterations = recommendationProperties.maxIterations();
        this.dampingFactor = recommendationProperties.dampingFactor();
    }

    /**
     * Best jobs for the candidate, highest score first. Empty when the candidate has no node; fails with a
     * {@code ClientException} when the projection is gone or does not contain the candidate.
     */
    public List<ScoredJob> personalizedRanking(String graphName, UUID candidateId, int limit) {
        try (Session session = driver.session(sessionConfig)) {
            return session.run(PERSONALIZED_PAGE_RANK, Map.of(
                "graphName", graphName,
                "candidateId", candidateId.toString(),
                "maxIterations", maxIterations,
                "dampingFactor", dampingFactor,
                "now", now(),
                "limit", limit
            )).list(record -> new ScoredJob(UUID.fromString(record.get("id").asString()), record.get("score").asDouble()));
        }
    }

    /** For each of the jobs that shares skills with the candidate's CV, those the CV is surest about. */
    public Map<UUID, List<String>> matchedSkills(UUID candidateId, Collection<UUID> jobIds, int limit) {
        try (Session session = driver.session(sessionConfig)) {
            return session.run(MATCHED_SKILLS, Map.of(
                "candidateId", candidateId.toString(),
                "jobIds", jobIds.stream().map(UUID::toString).toList(),
                "limit", limit
            )).stream().collect(Collectors.toMap(
                record -> UUID.fromString(record.get("id").asString()),
                record -> record.get("skills").asList(Value::asString)));
        }
    }

    /** Open jobs, newest first: what users get before the graph knows enough about them. */
    public List<UUID> newestJobs(int limit) {
        try (Session session = driver.session(sessionConfig)) {
            return session.run(NEWEST_JOBS, Map.of("now", now(), "limit", limit))
                .list(record -> UUID.fromString(record.get("id").asString()));
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
