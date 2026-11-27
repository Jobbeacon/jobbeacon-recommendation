package com.jobbeacon.recommendation.recommend;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reciprocal Rank Fusion: each source adds {@code weight / (k + rank)} for every job it ranks, rank 1 being its best.
 * Only ranks count, so sources whose scores mean different things (PageRank, title words, vector distance) can be
 * combined without scaling them, and a job several sources rank well comes first. A larger {@code k} flattens the
 * difference between ranks within a source.
 */
final class RankFusion {

    private RankFusion() {
    }

    /** Job ids of one source, best first, and how much the source counts. */
    record Source(List<UUID> jobIds, double weight) {
    }

    /** Best first; ties keep the order in which the sources first listed the jobs. */
    static List<ScoredJob> fuse(List<Source> sources, double k) {
        Map<UUID, Double> scores = new LinkedHashMap<>();
        for (Source source : sources) {
            if (source.weight() <= 0) {
                continue;
            }
            List<UUID> ids = source.jobIds().stream().distinct().toList();
            for (int rank = 1; rank <= ids.size(); rank++) {
                scores.merge(ids.get(rank - 1), source.weight() / (k + rank), Double::sum);
            }
        }
        return scores.entrySet().stream()
            .map(entry -> new ScoredJob(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparingDouble(ScoredJob::score).reversed())
            .toList();
    }
}
