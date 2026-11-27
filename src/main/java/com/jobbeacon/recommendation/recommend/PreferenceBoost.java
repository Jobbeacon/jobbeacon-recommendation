package com.jobbeacon.recommendation.recommend;

import com.jobbeacon.recommendation.backend.dto.BackendRecommendationContext.PreferenceMatch;

/**
 * How much a job's score changes with its fit to the user's job preference and the experience in their CV. Mismatches
 * lower a job rather than remove it: what a posting says is often incomplete, and a user with a narrow preference
 * should still get a list.
 */
final class PreferenceBoost {

    // Initial values, to be tuned against recommendation quality like the graph's relationship weights.
    private static final double LOCATION_MATCH = 1.5;
    private static final double LOCATION_MISMATCH = 0.2;
    private static final double EMPLOYMENT_TYPE_MISMATCH = 0.4;
    private static final double SALARY_MATCH = 1.3;
    private static final double SALARY_MISMATCH = 0.5;

    private static final int SOME_EXPERIENCE_MISSING_MONTHS = 12;
    private static final int MUCH_EXPERIENCE_MISSING_MONTHS = 36;
    private static final double SOME_EXPERIENCE_MISSING = 0.7;
    private static final double MUCH_EXPERIENCE_MISSING = 0.4;

    private PreferenceBoost() {
    }

    /** 1 for a job without a match, i.e. when the user has no preference. */
    static double factor(PreferenceMatch match) {
        if (match == null) {
            return 1.0;
        }
        // A matching employment type says little: nearly every job is full time.
        return pick(match.location(), LOCATION_MATCH, LOCATION_MISMATCH)
            * pick(match.employmentType(), 1.0, EMPLOYMENT_TYPE_MISMATCH)
            * pick(match.salary(), SALARY_MATCH, SALARY_MISMATCH)
            * experience(match.missingExperienceMonths());
    }

    /** Postings ask for a little more than they need, so up to a year short is no mismatch. */
    private static double experience(Integer missingMonths) {
        if (missingMonths == null || missingMonths <= SOME_EXPERIENCE_MISSING_MONTHS) {
            return 1.0;
        }
        return missingMonths <= MUCH_EXPERIENCE_MISSING_MONTHS ? SOME_EXPERIENCE_MISSING : MUCH_EXPERIENCE_MISSING;
    }

    private static double pick(Boolean matches, double whenTrue, double whenFalse) {
        if (matches == null) {
            return 1.0;
        }
        return matches ? whenTrue : whenFalse;
    }
}
