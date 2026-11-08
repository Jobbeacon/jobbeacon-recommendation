package com.jobbeacon.recommendation.interaction;

/**
 * Names double as Neo4j relationship types. APPLY_CLICKED is not "applied": applying happens on the source site, so
 * all JobBeacon can know is that the user followed the link.
 *
 * <p>There is deliberately no "clicked in a list": every such click opens the job page and is recorded as VIEWED,
 * so it would count the same action twice and favour jobs reached from lists over those reached by a shared link.
 */
public enum JobInteractionType {
    VIEWED,
    APPLY_CLICKED
}
