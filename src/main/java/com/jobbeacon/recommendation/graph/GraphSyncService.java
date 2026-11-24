package com.jobbeacon.recommendation.graph;

import com.jobbeacon.recommendation.backend.BackendClient;
import com.jobbeacon.recommendation.backend.dto.BackendCandidates;
import com.jobbeacon.recommendation.backend.dto.BackendJob;
import com.jobbeacon.recommendation.backend.dto.IdsRequest;
import com.jobbeacon.recommendation.config.GraphProperties;
import com.jobbeacon.recommendation.interaction.JobInteractionType;
import com.jobbeacon.recommendation.interaction.UserJobInteraction;
import com.jobbeacon.recommendation.interaction.UserJobInteractionRepository;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Pushes the current state of a set of jobs or candidates into Neo4j: for a job, the Job node, its Skill/Company nodes
 * and the REQUIRES_SKILL/POSTED_BY relationships; for a candidate, the Candidate node and its HAS_SKILL, SAVED and
 * interaction relationships. Jobs, users, CV skills and saved jobs come from the backend; interactions from this
 * service's database. Ids the backend no longer knows are removed from the graph.
 *
 * <p>Everything is derived from current state rather than from what changed, so syncing the same job twice is a
 * no-op. Job status is deliberately not stored: a job expires without any write, so queries filter on
 * {@code expires_at} instead.
 */
@Service
public class GraphSyncService {

    private static final List<String> CONSTRAINTS = List.of(
        "CREATE CONSTRAINT candidate_id IF NOT EXISTS FOR (c:Candidate) REQUIRE c.id IS UNIQUE",
        "CREATE CONSTRAINT job_id IF NOT EXISTS FOR (j:Job) REQUIRE j.id IS UNIQUE",
        "CREATE CONSTRAINT skill_id IF NOT EXISTS FOR (s:Skill) REQUIRE s.id IS UNIQUE",
        "CREATE CONSTRAINT company_id IF NOT EXISTS FOR (c:Company) REQUIRE c.id IS UNIQUE"
    );

    private static final String UPSERT_SKILLS = """
        UNWIND $skills AS row
        MERGE (s:Skill {id: row.id})
        SET s.name = row.name, s.normalized_name = row.normalized_name
        """;

    private static final String UPSERT_COMPANIES = """
        UNWIND $companies AS row
        MERGE (c:Company {id: row.id})
        SET c.name = row.name, c.normalized_name = row.normalized_name
        """;

    private static final String UPSERT_JOBS = """
        UNWIND $jobs AS row
        MERGE (j:Job {id: row.id})
        SET j.title = row.title,
            j.sources = row.sources,
            j.source_urls = row.source_urls,
            j.published_at = row.published_at,
            j.expires_at = row.expires_at
        """;

    private static final String PRUNE_REQUIRES_SKILL = """
        UNWIND $jobs AS row
        MATCH (:Job {id: row.id})-[r:REQUIRES_SKILL]->(s:Skill)
        WHERE NOT s.id IN row.skill_ids
        DELETE r
        """;

    private static final String MERGE_REQUIRES_SKILL = """
        UNWIND $jobs AS row
        MATCH (j:Job {id: row.id})
        UNWIND row.skill_ids AS skillId
        MATCH (s:Skill {id: skillId})
        MERGE (j)-[:REQUIRES_SKILL]->(s)
        """;

    private static final String PRUNE_POSTED_BY = """
        UNWIND $jobs AS row
        MATCH (:Job {id: row.id})-[r:POSTED_BY]->(c:Company)
        WHERE row.company_id IS NULL OR c.id <> row.company_id
        DELETE r
        """;

    private static final String MERGE_POSTED_BY = """
        UNWIND $jobs AS row
        WITH row WHERE row.company_id IS NOT NULL
        MATCH (j:Job {id: row.id})
        MATCH (c:Company {id: row.company_id})
        MERGE (j)-[:POSTED_BY]->(c)
        """;

    private static final String DELETE_JOBS = """
        UNWIND $deletedIds AS id
        MATCH (j:Job {id: id})
        DETACH DELETE j
        """;

    private static final String UPSERT_CANDIDATES = """
        UNWIND $candidates AS row
        MERGE (:Candidate {id: row.id})
        """;

    private static final String PRUNE_HAS_SKILL = """
        UNWIND $candidates AS row
        MATCH (:Candidate {id: row.id})-[r:HAS_SKILL]->(s:Skill)
        WHERE NOT s.id IN [skill IN row.skills | skill.id]
        DELETE r
        """;

    private static final String MERGE_HAS_SKILL = """
        UNWIND $candidates AS row
        MATCH (c:Candidate {id: row.id})
        UNWIND row.skills AS skill
        MATCH (s:Skill {id: skill.id})
        MERGE (c)-[r:HAS_SKILL]->(s)
        SET r.confidence = skill.confidence,
            r.experience_months = skill.experience_months,
            r.last_used_at = skill.last_used_at
        """;

    private static final String PRUNE_JOB_RELATIONSHIP = """
        UNWIND $candidates AS row
        MATCH (:Candidate {id: row.id})-[r:%1$s]->(j:Job)
        WHERE NOT j.id IN [job IN row.%1$s | job.job_id]
        DELETE r
        """;

    private static final String MERGE_JOB_RELATIONSHIP = """
        UNWIND $candidates AS row
        MATCH (c:Candidate {id: row.id})
        UNWIND row.%1$s AS job
        MATCH (j:Job {id: job.job_id})
        MERGE (c)-[r:%1$s]->(j)
        SET r.count = job.count, r.first_at = job.first_at, r.last_at = job.last_at
        """;

    private static final String DELETE_CANDIDATES = """
        UNWIND $deletedIds AS id
        MATCH (c:Candidate {id: id})
        DETACH DELETE c
        """;

    private static final String FIND_MISSING_JOBS = """
        UNWIND $jobIds AS id
        OPTIONAL MATCH (j:Job {id: id})
        WITH id, j
        WHERE j IS NULL
        RETURN id
        """;

    private static final String SAVED = "SAVED";

    // Relationship types can't be query parameters; these come from constants, never from user input.
    private static final List<String> JOB_RELATIONSHIPS = Stream.concat(
        Stream.of(SAVED),
        Arrays.stream(JobInteractionType.values()).map(Enum::name)
    ).toList();

    private static final List<String> CANDIDATE_QUERIES = Stream.of(
        Stream.of(UPSERT_SKILLS, UPSERT_CANDIDATES, PRUNE_HAS_SKILL, MERGE_HAS_SKILL),
        JOB_RELATIONSHIPS.stream().flatMap(type -> Stream.of(
            PRUNE_JOB_RELATIONSHIP.formatted(type),
            MERGE_JOB_RELATIONSHIP.formatted(type)
        )),
        Stream.of(DELETE_CANDIDATES)
    ).flatMap(queries -> queries).toList();

    /** The backend's internal API rejects requests with more ids than this. */
    static final int MAX_IDS_PER_REQUEST = 1000;

    private static final String HAS_DATA = "RETURN EXISTS { MATCH (n) } AS hasData";

    private final Driver driver;
    private final BackendClient backendClient;
    private final UserJobInteractionRepository interactionRepository;
    private final SessionConfig sessionConfig;
    private final Duration interactionWindow;
    private volatile boolean schemaReady;

    public GraphSyncService(
        Driver driver,
        BackendClient backendClient,
        UserJobInteractionRepository interactionRepository,
        GraphProperties properties
    ) {
        this.driver = driver;
        this.backendClient = backendClient;
        this.interactionRepository = interactionRepository;
        this.sessionConfig = SessionConfig.forDatabase(properties.database());
        this.interactionWindow = properties.interactionWindow();
    }

    /** True when the graph holds at least one node; constraints and indexes alone do not count. */
    public boolean hasData() {
        try (Session session = driver.session(sessionConfig)) {
            return session.executeRead(tx -> tx.run(HAS_DATA).single().get("hasData").asBoolean());
        }
    }

    public void syncJobs(Collection<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return;
        }
        // Missing jobs gathered from a whole candidate batch can exceed what the backend accepts in one request.
        if (jobIds.size() > MAX_IDS_PER_REQUEST) {
            List<UUID> ids = List.copyOf(jobIds);
            for (int from = 0; from < ids.size(); from += MAX_IDS_PER_REQUEST) {
                syncJobs(ids.subList(from, Math.min(from + MAX_IDS_PER_REQUEST, ids.size())));
            }
            return;
        }
        List<BackendJob> jobs = backendClient.findJobs(new IdsRequest(jobIds));

        Map<String, Map<String, Object>> skills = new LinkedHashMap<>();
        Map<String, Map<String, Object>> companies = new LinkedHashMap<>();
        List<Map<String, Object>> jobRows = new ArrayList<>();
        for (BackendJob job : jobs) {
            List<String> skillIds = new ArrayList<>();
            for (BackendJob.Skill skill : nullToEmpty(job.skills())) {
                String skillId = skill.id().toString();
                skillIds.add(skillId);
                skills.computeIfAbsent(skillId, id -> row(
                    "id", id, "name", skill.name(), "normalized_name", skill.normalizedName()));
            }
            String companyId = job.companyId() == null ? null : job.companyId().toString();
            if (companyId != null) {
                companies.computeIfAbsent(companyId, id -> row(
                    "id", id, "name", job.companyName(), "normalized_name", normalizeName(job.companyName())));
            }
            List<BackendJob.Source> sources = nullToEmpty(job.sources());
            jobRows.add(row(
                "id", job.id().toString(),
                "title", job.title(),
                "company_id", companyId,
                "skill_ids", skillIds,
                "sources", sources.stream().map(BackendJob.Source::domain).filter(d -> d != null).distinct().toList(),
                "source_urls", sources.stream().map(BackendJob.Source::jobUrl).filter(u -> u != null).distinct().toList(),
                "published_at", toDateTime(job.postedDate()),
                "expires_at", toDateTime(job.expiredDate())
            ));
        }

        Set<String> deletedIds = jobIds.stream().map(UUID::toString).collect(Collectors.toCollection(LinkedHashSet::new));
        jobs.forEach(job -> deletedIds.remove(job.id().toString()));

        Map<String, Object> params = Map.of(
            "skills", List.copyOf(skills.values()),
            "companies", List.copyOf(companies.values()),
            "jobs", jobRows,
            "deletedIds", List.copyOf(deletedIds)
        );

        ensureSchema();
        try (Session session = driver.session(sessionConfig)) {
            // One transaction per batch: a job is never left half-written (e.g. node without its skills).
            session.executeWriteWithoutResult(tx -> {
                for (String cypher : List.of(UPSERT_SKILLS, UPSERT_COMPANIES, UPSERT_JOBS, PRUNE_REQUIRES_SKILL,
                    MERGE_REQUIRES_SKILL, PRUNE_POSTED_BY, MERGE_POSTED_BY, DELETE_JOBS)) {
                    tx.run(cypher, params).consume();
                }
            });
        }
    }

    public void syncCandidates(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        BackendCandidates source = backendClient.findCandidates(new IdsRequest(userIds));
        Set<UUID> existingIds = new HashSet<>(nullToEmpty(source.existingUserIds()));
        Map<UUID, List<BackendCandidates.Skill>> skillsByUser = nullToEmpty(source.skills()).stream()
            .collect(Collectors.groupingBy(BackendCandidates.Skill::userId));
        Map<UUID, List<BackendCandidates.SavedJob>> savedByUser = nullToEmpty(source.savedJobs()).stream()
            .collect(Collectors.groupingBy(BackendCandidates.SavedJob::userId));
        Instant interactionsSince = Instant.now().minus(interactionWindow);
        Map<UUID, List<UserJobInteraction>> interactionsByUser =
            interactionRepository.findRecentByUserIds(userIds, interactionsSince).stream()
                .collect(Collectors.groupingBy(interaction -> interaction.getId().getUserId()));

        Map<String, Map<String, Object>> skills = new LinkedHashMap<>();
        Set<UUID> jobIds = new LinkedHashSet<>();
        List<Map<String, Object>> candidateRows = new ArrayList<>();
        List<String> deletedIds = new ArrayList<>();
        for (UUID userId : userIds) {
            if (!existingIds.contains(userId)) {
                deletedIds.add(userId.toString());
                continue;
            }
            List<Map<String, Object>> skillRows = new ArrayList<>();
            for (BackendCandidates.Skill skill : skillsByUser.getOrDefault(userId, List.of())) {
                String skillId = skill.skillId().toString();
                skills.computeIfAbsent(skillId, id -> row(
                    "id", id, "name", skill.name(), "normalized_name", skill.normalizedName()));
                skillRows.add(row(
                    "id", skillId,
                    "confidence", skill.confidence() == null ? null : skill.confidence().doubleValue(),
                    "experience_months", skill.experienceMonths(),
                    "last_used_at", skill.lastUsedAt()
                ));
            }

            // Every type gets a list, even empty: an absent list would make its prune query keep stale relationships.
            Map<String, Object> candidate = new HashMap<>();
            JOB_RELATIONSHIPS.forEach(type -> candidate.put(type, new ArrayList<Map<String, Object>>()));
            candidate.put("id", userId.toString());
            candidate.put("skills", skillRows);
            for (BackendCandidates.SavedJob saved : savedByUser.getOrDefault(userId, List.of())) {
                jobIds.add(saved.jobId());
                addJobRelationship(candidate, SAVED, saved.jobId(), 1, saved.savedAt(), saved.savedAt());
            }
            for (UserJobInteraction interaction : interactionsByUser.getOrDefault(userId, List.of())) {
                UUID jobId = interaction.getId().getJobId();
                jobIds.add(jobId);
                addJobRelationship(candidate, interaction.getId().getInteractionType().name(), jobId,
                    interaction.getInteractionCount(), interaction.getFirstAt(), interaction.getLastAt());
            }
            candidateRows.add(candidate);
        }

        Map<String, Object> params = Map.of(
            "skills", List.copyOf(skills.values()),
            "candidates", candidateRows,
            "deletedIds", deletedIds
        );

        ensureSchema();
        syncMissingJobs(jobIds);
        try (Session session = driver.session(sessionConfig)) {
            session.executeWriteWithoutResult(tx -> {
                for (String cypher : CANDIDATE_QUERIES) {
                    tx.run(cypher, params).consume();
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static void addJobRelationship(
        Map<String, Object> candidate, String type, UUID jobId, int count, Instant firstAt, Instant lastAt
    ) {
        ((List<Map<String, Object>>) candidate.get(type)).add(row(
            "job_id", jobId.toString(),
            "count", count,
            "first_at", toDateTime(firstAt),
            "last_at", toDateTime(lastAt)
        ));
    }

    // A relationship to a job with no node would be dropped silently while its queue row still counted as processed.
    // That happens when the job predates the outbox and was never backfilled, so sync those jobs here first.
    private void syncMissingJobs(Set<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return;
        }
        List<String> ids = jobIds.stream().map(UUID::toString).toList();
        List<UUID> missing;
        try (Session session = driver.session(sessionConfig)) {
            missing = session.executeRead(tx -> tx.run(FIND_MISSING_JOBS, Map.of("jobIds", ids))
                .list(record -> UUID.fromString(record.get("id").asString())));
        }
        syncJobs(missing);
    }

    // Created lazily rather than at startup so the service still boots while Neo4j is down.
    private void ensureSchema() {
        if (schemaReady) {
            return;
        }
        try (Session session = driver.session(sessionConfig)) {
            CONSTRAINTS.forEach(constraint -> session.run(constraint).consume());
        }
        schemaReady = true;
    }

    // A list the backend left out is treated as empty rather than failing the whole batch.
    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String normalizeName(String name) {
        return name == null ? null : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static OffsetDateTime toDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    // Map.of rejects null values, and nullable fields (e.g. expires_at) are expected here.
    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }
}
