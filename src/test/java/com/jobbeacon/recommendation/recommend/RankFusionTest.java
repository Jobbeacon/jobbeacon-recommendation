package com.jobbeacon.recommendation.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.jobbeacon.recommendation.recommend.RankFusion.Source;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RankFusionTest {

  private final UUID a = UUID.randomUUID();
  private final UUID b = UUID.randomUUID();
  private final UUID c = UUID.randomUUID();

  @Test
  void eachSourceAddsWeightOverKPlusRank() {
    List<ScoredJob> fused = RankFusion.fuse(List.of(new Source(List.of(a, b), 1), new Source(List.of(b), 2)), 60);

    assertThat(fused).extracting(ScoredJob::jobId).containsExactly(b, a);
    assertThat(fused.get(0).score()).isCloseTo(1.0 / 62 + 2.0 / 61, within(1e-12));
    assertThat(fused.get(1).score()).isCloseTo(1.0 / 61, within(1e-12));
  }

  @Test
  void aSourceOfWeightZeroIsLeftOut() {
    assertThat(RankFusion.fuse(List.of(new Source(List.of(a), 1), new Source(List.of(c, a), 0)), 60))
        .extracting(ScoredJob::jobId).containsExactly(a);
  }

  @Test
  void aJobListedTwiceBySourceCountsOnce() {
    assertThat(RankFusion.fuse(List.of(new Source(List.of(a, a, b), 1)), 60))
        .extracting(ScoredJob::score).containsExactly(1.0 / 61, 1.0 / 62);
  }

  @Test
  void tiesKeepTheOrderOfTheFirstListing() {
    assertThat(RankFusion.fuse(List.of(new Source(List.of(a), 1), new Source(List.of(c), 1)), 60))
        .extracting(ScoredJob::jobId).containsExactly(a, c);
  }
}
