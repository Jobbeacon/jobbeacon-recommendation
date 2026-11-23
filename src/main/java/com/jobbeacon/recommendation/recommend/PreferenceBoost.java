package com.jobbeacon.recommendation.recommend;

import com.jobbeacon.recommendation.backend.dto.BackendRecommendationContext.PreferenceMatch;

/**
 * How much a job's score changes with its fit to the user's job preference. Mismatches lower a job rather than remove
 * it: what a posting says is often incomplete, and a user with a narrow preference should still get a list.
 */
final class PreferenceBoost {

    // Initial values, to be tuned against recommendation quality like the graph's relationship weights.
    private static final double TITLE_MATCH = 2.0;
    private static final double LOCATION_MATCH = 1.5;
    private static final double LOCATION_MISMATCH = 0.2;
    private static final double EMPLOYMENT_TYPE_MISMATCH = 0.4;
    private static final double SALARY_MATCH = 1.3;
    private static final double SALARY_MISMATCH = 0.5;

    private PreferenceBoost() {
    }

    /** 1 for a job without a match, i.e. when the user has no preference. */
    static double factor(PreferenceMatch match) {
        if (match == null) {
            return 1.0;
        }
        // A title that differs says little (the same job goes by many names), so only a match counts. A matching
        // employment type says little too: nearly every job is full time.
        return pick(match.title(), TITLE_MATCH, 1.0)
            * pick(match.location(), LOCATION_MATCH, LOCATION_MISMATCH)
            * pick(match.employmentType(), 1.0, EMPLOYMENT_TYPE_MISMATCH)
            * pick(match.salary(), SALARY_MATCH, SALARY_MISMATCH);
    }

    private static double pick(Boolean matches, double whenTrue, double whenFalse) {
        if (matches == null) {
            return 1.0;
        }
        return matches ? whenTrue : whenFalse;
    }
}
