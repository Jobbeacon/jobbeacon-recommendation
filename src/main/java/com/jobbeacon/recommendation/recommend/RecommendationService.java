package com.jobbeacon.recommendation.recommend;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jobbeacon.recommendation.backend.BackendClient;
import com.jobbeacon.recommendation.backend.dto.BackendJobSummary;
import com.jobbeacon.recommendation.backend.dto.BackendRecommendationContext;
import com.jobbeacon.recommendation.backend.dto.BackendRecommendationContext.PreferenceMatch;
import com.jobbeacon.recommendation.backend.dto.IdsRequest;
import com.jobbeacon.recommendation.config.RecommendationProperties;
import com.jobbeacon.recommendation.graph.GraphProjectionService;
import com.jobbeacon.recommendation.interaction.JobInteractionType;
import com.jobbeacon.recommendation.interaction.UserJobInteraction;
import com.jobbeacon.recommendation.interaction.UserJobInteractionRepository;
import com.jobbeacon.recommendation.web.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.exceptions.ClientException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Builds a user's recommended jobs:
 * <ol>
 *   <li>Rank: Personalized PageRank on the active projection, cached per user and projection, so it runs at most
 *   once per user between two rebuilds. A new projection has a new name and thus starts fresh cache entries.</li>
 *   <li>Fill: when the graph knows too little about the user (no CV, no activity, or no projection yet) the pool is
 *   topped up with the newest jobs.</li>
 *   <li>Adjust: what changed since the projection was built is read live. Saved jobs and recent apply clicks are
 *   hidden, jobs the user has seen move to the end, and the job preference raises or lowers the rest (see
 *   {@link PreferenceBoost}).</li>
 *   <li>Shuffle: personalized jobs first, then the newest, each part in a random order weighted by score (see
 *   {@link WeightedShuffle}), so every request shows a different list.</li>
 * </ol>
 */
@Slf4j
@Service
public class RecommendationService {

    static final String INVALID_SIZE = "recommendation_invalid_size";
    /** What Neo4j reports when a GDS procedure itself fails, e.g. for a projection or source node that isn't there. */
    static final String PROCEDURE_FAILED = "Neo.ClientError.Procedure.ProcedureCallFailed";
    /** How many cards to ask the backend for; it accepts 1000. */
    private static final int MAX_CARDS = 500;
    private static final int MAX_MATCHED_SKILLS = 3;
    private static final String NO_PROJECTION = "";

    private final RecommendationGraphRepository graphRepository;
    private final GraphProjectionService projectionService;
    private final UserJobInteractionRepository interactionRepository;
    private final BackendClient backendClient;
    private final RecommendationProperties properties;
    private final Cache<RankingKey, List<ScoredJob>> rankings;
    private final Cache<String, List<UUID>> newestJobs;

    public RecommendationService(
        RecommendationGraphRepository graphRepository,
        GraphProjectionService projectionService,
        UserJobInteractionRepository interactionRepository,
        BackendClient backendClient,
        RecommendationProperties properties
    ) {
        this.graphRepository = graphRepository;
        this.projectionService = projectionService;
        this.interactionRepository = interactionRepository;
        this.backendClient = backendClient;
        this.properties = properties;
        this.rankings = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(properties.cacheTtl())
            .build();
        this.newestJobs = Caffeine.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(properties.fallbackTtl())
            .build();
    }

    public RecommendedJobsResponse recommend(UUID userId, int size) {
        if (size < 1 || size > properties.maxSize()) {
            throw ApiException.badRequest(INVALID_SIZE);
        }

        List<ScoredJob> ranking = ranking(userId);
        // Fetched with room for the jobs that are filtered out below, which come out of the same list.
        List<UUID> newest = newestJobs.get(NO_PROJECTION, key -> graphRepository.newestJobs(properties.poolSize() * 2));
        List<UserJobInteraction> interactions = interactionRepository.findByUserIdOldestFirst(userId);
        List<UUID> candidates = Stream.concat(ranking.stream().map(ScoredJob::jobId), newest.stream()).distinct().toList();
        BackendRecommendationContext context =
            backendClient.findRecommendationContext(new BackendRecommendationContext.Request(userId, candidates));
        Map<UUID, PreferenceMatch> matches = context.matches().stream()
            .collect(Collectors.toMap(PreferenceMatch::jobId, Function.identity(), (first, second) -> first));

        Set<UUID> hidden = new HashSet<>(context.savedJobIds());
        Instant appliedSince = Instant.now().minus(properties.appliedHiddenFor());
        interactions.stream()
            .filter(interaction -> interaction.getId().getInteractionType() == JobInteractionType.APPLY_CLICKED)
            .filter(interaction -> interaction.getLastAt().isAfter(appliedSince))
            .forEach(interaction -> hidden.add(interaction.getId().getJobId()));
        Set<UUID> seen = interactions.stream()
            .map(interaction -> interaction.getId().getJobId())
            .filter(jobId -> !hidden.contains(jobId))
            .collect(Collectors.toCollection(LinkedHashSet::new));

        List<ScoredJob> personalized = ranking.stream()
            .filter(job -> !hidden.contains(job.jobId()) && !seen.contains(job.jobId()))
            .map(job -> adjusted(job, matches))
            .sorted(Comparator.comparingDouble(ScoredJob::score).reversed())
            .toList();
        List<ScoredJob> fallback = fallback(newest, personalized, hidden, seen, matches);

        RandomGenerator random = ThreadLocalRandom.current();
        // Seen jobs only show up when nothing else is left, the one seen longest ago first.
        List<UUID> ordered = Stream.of(
                WeightedShuffle.shuffle(personalized, properties.samplingTemperature(), random).stream().map(ScoredJob::jobId),
                WeightedShuffle.shuffle(fallback, properties.samplingTemperature(), random).stream().map(ScoredJob::jobId),
                seen.stream())
            .flatMap(ids -> ids)
            .limit(MAX_CARDS)
            .toList();

        List<BackendJobSummary> content = cards(ordered, size);
        List<UUID> shown = content.stream().map(BackendJobSummary::id).toList();
        Set<UUID> personalizedIds = personalized.stream().map(ScoredJob::jobId).collect(Collectors.toSet());
        boolean anyPersonalized = shown.stream().anyMatch(id ->
            personalizedIds.contains(id) || PreferenceBoost.factor(matches.get(id)) > 1.0);
        Map<UUID, List<String>> matchedSkills = shown.isEmpty()
            ? Map.of()
            : graphRepository.matchedSkills(userId, shown, MAX_MATCHED_SKILLS);
        return new RecommendedJobsResponse(content, anyPersonalized, matchedSkills);
    }

    private List<ScoredJob> ranking(UUID userId) {
        String graphName = projectionService.activeGraphName().orElse(NO_PROJECTION);
        List<ScoredJob> cached = rankings.getIfPresent(new RankingKey(userId, graphName));
        if (cached != null) {
            return cached;
        }
        List<ScoredJob> ranking;
        try {
            ranking = rank(userId, graphName);
        } catch (ClientException e) {
            // Anything else (a missing GDS plugin, bad credentials, a broken query) is a fault to surface, not a user
            // without a ranking.
            if (!PROCEDURE_FAILED.equals(e.code())) {
                throw e;
            }
            // The projection may have been replaced (and dropped) between reading its name and running PageRank.
            String current = projectionService.activeGraphName().orElse(NO_PROJECTION);
            if (current.equals(graphName)) {
                // Normally a user whose every relationship is older than the interaction window or points at expired
                // jobs, and so is not in the projection. GDS reports other failures with the same code, hence the
                // warning, and hence no caching: the next request tries again.
                log.warn("PageRank failed for user {}, using the newest jobs: {}", userId, e.getMessage());
                return List.of();
            }
            graphName = current;
            ranking = rank(userId, graphName);
        }
        rankings.put(new RankingKey(userId, graphName), ranking);
        return ranking;
    }

    private List<ScoredJob> rank(UUID userId, String graphName) {
        return graphName.equals(NO_PROJECTION)
            ? List.of()
            : graphRepository.personalizedRanking(graphName, userId, properties.poolSize());
    }

    /** The best of the newest jobs that are not in the list already, as many as the pool is short of. */
    private List<ScoredJob> fallback(
        List<UUID> newest,
        List<ScoredJob> personalized,
        Set<UUID> hidden,
        Set<UUID> seen,
        Map<UUID, PreferenceMatch> matches
    ) {
        Set<UUID> listed = personalized.stream().map(ScoredJob::jobId).collect(Collectors.toSet());
        List<UUID> unlisted = newest.stream()
            .filter(id -> !listed.contains(id) && !hidden.contains(id) && !seen.contains(id))
            .toList();
        // Newer jobs weigh more, so they still tend to come first after the shuffle.
        return IntStream.range(0, unlisted.size())
            .mapToObj(rank -> adjusted(new ScoredJob(unlisted.get(rank), 1.0 / (rank + 1)), matches))
            .sorted(Comparator.comparingDouble(ScoredJob::score).reversed())
            .limit(Math.max(0, properties.poolSize() - personalized.size()))
            .toList();
    }

    private static ScoredJob adjusted(ScoredJob job, Map<UUID, PreferenceMatch> matches) {
        return new ScoredJob(job.jobId(), job.score() * PreferenceBoost.factor(matches.get(job.jobId())));
    }

    /**
     * The first {@code size} cards in the given order. Jobs the backend no longer has or that expired since the last
     * sync are skipped, and so are copies of a card already in the list (the crawler stored some postings twice, and
     * companies repost the same opening); later jobs take their place.
     */
    private List<BackendJobSummary> cards(Collection<UUID> jobIds, int size) {
        if (jobIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, BackendJobSummary> byId = backendClient.findJobSummaries(new IdsRequest(jobIds)).stream()
            .collect(Collectors.toMap(BackendJobSummary::id, Function.identity(), (first, second) -> first));
        Set<CardKey> listed = new HashSet<>();
        return jobIds.stream()
            .map(byId::get)
            .filter(Objects::nonNull)
            .filter(card -> listed.add(CardKey.of(card)))
            .limit(size)
            .toList();
    }

    private record RankingKey(UUID userId, String graphName) {
    }

    /** What makes two cards look the same to a user. */
    private record CardKey(String title, String companyName, Set<String> location) {

        static CardKey of(BackendJobSummary card) {
            return new CardKey(
                card.title() == null ? card.id().toString() : card.title().trim().toLowerCase(),
                card.companyName(),
                card.location() == null ? Set.of() : Set.copyOf(card.location()));
        }
    }
}
