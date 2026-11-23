package com.jobbeacon.recommendation.recommend;

import java.util.UUID;

/** A job and how strongly it is recommended; only the order of scores matters, not their scale. */
public record ScoredJob(UUID jobId, double score) {
}
