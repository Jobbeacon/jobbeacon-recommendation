package com.jobbeacon.recommendation.cdc;

import com.jobbeacon.recommendation.config.CdcProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Whether changes from the backend still arrive. The stream carries a heartbeat every 10 seconds even when nothing
 * changes, so silence means it is broken: the graph goes stale and the replication slot makes Postgres keep its WAL.
 */
@Component
public class CdcHealth {

    static final Duration MAX_SILENCE = Duration.ofMinutes(2);

    private final boolean enabled;
    private final Clock clock;
    private volatile Instant lastEventAt;

    @Autowired
    public CdcHealth(CdcProperties properties) {
        this(properties.enabled(), Clock.systemUTC());
    }

    CdcHealth(boolean enabled, Clock clock) {
        this.enabled = enabled;
        this.clock = clock;
    }

    void eventReceived() {
        lastEventAt = clock.instant();
    }

    /** False until the first event, so that a version that cannot stream at all never passes as healthy. */
    public boolean isHealthy() {
        Instant last = lastEventAt;
        return !enabled || (last != null && !last.isBefore(clock.instant().minus(MAX_SILENCE)));
    }
}
