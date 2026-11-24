package com.jobbeacon.recommendation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tuning for {@code GET /api/v1/me/recommended-jobs}.
 *
 * @param poolSize            how many jobs a user's list is drawn from: the best Personalized PageRank results,
 *                            topped up with the newest jobs when there are fewer
 * @param titlePoolSize       how many jobs found by title (see {@code BackendClient#findTitleJobs}) join that pool
 * @param maxSize             most jobs a request may ask for
 * @param samplingTemperature how much each reload reshuffles the pool. A job's chance of being picked next grows
 *                            with {@code score ^ (1 / temperature)}: near 0 always gives the plain ranking, 1 picks in
 *                            proportion to the score, higher values approach a uniform shuffle
 * @param cacheTtl            how long a user's ranking is kept. A new projection starts a new entry anyway, so this
 *                            only needs to match the refresh interval
 * @param fallbackTtl         how long the shared list of newest jobs is kept
 * @param appliedHiddenFor    how long a job stays hidden after the user followed its apply link. They may not have applied
 *                            in the end, so it can come back afterwards, behind jobs they have not seen
 * @param maxIterations       PageRank iterations
 * @param dampingFactor       PageRank damping factor: the chance that a walk goes on rather than jumping back to the
 *                            user, so lower values keep results closer to the user's own skills and jobs
 */
@ConfigurationProperties(prefix = "app.recommendation")
public record RecommendationProperties(
        int poolSize,
        int titlePoolSize,
        int maxSize,
        double samplingTemperature,
        Duration cacheTtl,
        Duration fallbackTtl,
        Duration appliedHiddenFor,
        int maxIterations,
        double dampingFactor
) {
}
