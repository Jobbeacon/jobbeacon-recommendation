package com.jobbeacon.recommendation.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CdcHealthTest {

  private static final Instant START = Instant.parse("2026-11-23T00:00:00Z");

  private final Clock clock = mock(Clock.class);

  @Test
  void unhealthyUntilTheFirstEventAndAgainAfterTooLongASilence() {
    CdcHealth health = new CdcHealth(true, clock);
    when(clock.instant()).thenReturn(START);
    assertThat(health.isHealthy()).isFalse();

    health.eventReceived();
    assertThat(health.isHealthy()).isTrue();

    when(clock.instant()).thenReturn(START.plus(CdcHealth.MAX_SILENCE));
    assertThat(health.isHealthy()).isTrue();
    when(clock.instant()).thenReturn(START.plus(CdcHealth.MAX_SILENCE).plus(Duration.ofSeconds(1)));
    assertThat(health.isHealthy()).isFalse();
  }

  @Test
  void healthyWhenCdcIsTurnedOff() {
    assertThat(new CdcHealth(false, clock).isHealthy()).isTrue();
  }
}
