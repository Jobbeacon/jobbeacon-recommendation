package com.jobbeacon.recommendation.recommend;

import java.util.Comparator;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Orders jobs by repeatedly drawing one of those left, with a chance that grows with {@code score ^ (1 / temperature)}
 * (the Gumbel-max trick: sorting by {@code log(score) / temperature + Gumbel noise} gives exactly that order). Higher
 * scores still tend to come first, but every call gives a different order, which is what makes a reload show other jobs.
 */
final class WeightedShuffle {

    private WeightedShuffle() {
    }

    /** {@code jobs} must have positive scores. */
    static List<ScoredJob> shuffle(List<ScoredJob> jobs, double temperature, RandomGenerator random) {
        if (temperature <= 0 || jobs.size() < 2) {
            return jobs;
        }
        record Keyed(ScoredJob job, double key) {
        }
        return jobs.stream()
            .map(job -> new Keyed(job, Math.log(job.score()) / temperature + gumbel(random)))
            .sorted(Comparator.comparingDouble(Keyed::key).reversed())
            .map(Keyed::job)
            .toList();
    }

    private static double gumbel(RandomGenerator random) {
        // nextDouble() can return 0, whose log is -infinity; 1 - u is in (0, 1].
        double u = 1.0 - random.nextDouble();
        return -Math.log(-Math.log(u) + Double.MIN_VALUE);
    }
}
