package com.jobbeacon.recommendation.recommend;

import com.jobbeacon.recommendation.backend.dto.BackendRecommendationContext.CvTitle;
import com.jobbeacon.recommendation.backend.dto.BackendRecommendationContext.PreferenceMatch;

/**
 * How much a job's score changes with its fit to the user's job preference, the experience in their CV, and the titles
 * in their CV while they state no desired title. Mismatches lower a job rather than remove
 * it: what a posting says is often incomplete, and a user with a narrow preference should still get a list.
 */
final class PreferenceBoost {

    // Initial values, to be tuned against recommendation quality like the graph's relationship weights.
    // Titles add to a score of at most 1 (see titleBonus). Kept below 1 so that the graph still has a say.
    private static final double TITLE_MATCH = 0.9;
    // Weaker than a desired title: what someone did is not always what they want next.
    private static final double CURRENT_CV_TITLE_MATCH = 0.7;
    private static final double PAST_CV_TITLE_MATCH = 0.3;
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

    /**
     * What a matching title adds to a score between 0 and 1. Added rather than multiplied because PageRank scores fall off
     * steeply: no sensible factor would lift a job the graph barely reaches. A title that differs says little (the same
     * job goes by many names), so it takes nothing away.
     */
    static double titleBonus(PreferenceMatch match) {
        if (match == null) {
            return 0.0;
        }
        return Boolean.TRUE.equals(match.title()) ? TITLE_MATCH : cvTitle(match.cvTitle());
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

    private static double cvTitle(CvTitle matched) {
        if (matched == null) {
            return 0.0;
        }
        return matched == CvTitle.CURRENT ? CURRENT_CV_TITLE_MATCH : PAST_CV_TITLE_MATCH;
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
