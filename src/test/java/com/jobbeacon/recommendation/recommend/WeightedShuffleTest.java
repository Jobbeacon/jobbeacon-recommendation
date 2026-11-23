package com.jobbeacon.recommendation.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class WeightedShuffleTest {

  private static final List<ScoredJob> JOBS = IntStream.range(0, 20)
      .mapToObj(rank -> new ScoredJob(UUID.randomUUID(), 1.0 / (rank + 1)))
      .toList();

  @Test
  void repeatedShufflesGiveDifferentOrders() {
    Set<List<ScoredJob>> orders = new HashSet<>();
    for (long seed = 0; seed < 10; seed++) {
      orders.add(WeightedShuffle.shuffle(JOBS, 0.5, new Random(seed)));
    }
    assertThat(orders).hasSizeGreaterThan(1);
  }

  @Test
  void keepsEveryJobExactlyOnce() {
    assertThat(WeightedShuffle.shuffle(JOBS, 0.5, new Random(7))).containsExactlyInAnyOrderElementsOf(JOBS);
  }

  @Test
  void zeroTemperatureKeepsTheRanking() {
    assertThat(WeightedShuffle.shuffle(JOBS, 0, new Random(7))).isEqualTo(JOBS);
  }

  @Test
  void higherScoresTendToComeFirst() {
    int bestFirst = 0;
    int worstFirst = 0;
    for (long seed = 0; seed < 2000; seed++) {
      ScoredJob first = WeightedShuffle.shuffle(JOBS, 0.5, new Random(seed)).get(0);
      bestFirst += first.equals(JOBS.get(0)) ? 1 : 0;
      worstFirst += first.equals(JOBS.get(JOBS.size() - 1)) ? 1 : 0;
    }
    // With temperature 0.5 the chance grows with score^2: the best job leads about 63% of the time, the worst 0.16%.
    assertThat(bestFirst).isGreaterThan(1000);
    assertThat(worstFirst).isLessThan(bestFirst / 50);
  }

  @Test
  void lowerTemperatureReshufflesLess() {
    assertThat(displacement(0.2)).isLessThan(displacement(1.0));
  }

  /** How far jobs move from their rank, summed over many seeds. */
  private static long displacement(double temperature) {
    long total = 0;
    for (long seed = 0; seed < 200; seed++) {
      List<ScoredJob> shuffled = WeightedShuffle.shuffle(JOBS, temperature, new Random(seed));
      for (int i = 0; i < shuffled.size(); i++) {
        total += Math.abs(JOBS.indexOf(shuffled.get(i)) - i);
      }
    }
    return total;
  }
}
