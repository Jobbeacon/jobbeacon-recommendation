package com.jobbeacon.recommendation.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbeacon.recommendation.backend.BackendClient;
import com.jobbeacon.recommendation.backend.dto.BackendJobSummary;
import com.jobbeacon.recommendation.backend.dto.BackendRecommendationContext;
import com.jobbeacon.recommendation.backend.dto.BackendRecommendationContext.PreferenceMatch;
import com.jobbeacon.recommendation.backend.dto.IdsRequest;
import com.jobbeacon.recommendation.config.RecommendationProperties;
import com.jobbeacon.recommendation.graph.GraphProjectionService;
import com.jobbeacon.recommendation.interaction.JobInteractionType;
import com.jobbeacon.recommendation.interaction.UserJobInteraction;
import com.jobbeacon.recommendation.interaction.UserJobInteractionRepository;
import com.jobbeacon.recommendation.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.exceptions.ClientException;

class RecommendationServiceTest {

  private static final UUID USER = UUID.randomUUID();
  private static final int POOL = 10;

  private final RecommendationGraphRepository graphRepository = mock(RecommendationGraphRepository.class);
  private final GraphProjectionService projectionService = mock(GraphProjectionService.class);
  private final UserJobInteractionRepository interactionRepository = mock(UserJobInteractionRepository.class);
  private final BackendClient backendClient = mock(BackendClient.class);
  private final RecommendationService service = service(0.5);

  @BeforeEach
  void setUp() {
    when(projectionService.activeGraphName()).thenReturn(Optional.of("graph-1"));
    when(graphRepository.newestJobs(anyInt())).thenReturn(List.of());
    givenContext(List.of());
    // The backend knows every job and answers in reverse order, to check that the response keeps the list's order.
    when(backendClient.findJobSummaries(any())).thenAnswer(call -> {
      List<BackendJobSummary> cards = new ArrayList<>(((IdsRequest) call.getArgument(0)).ids().stream()
          .map(RecommendationServiceTest::card).toList());
      return cards.reversed();
    });
  }

  @Test
  void savedJobsAndRecentApplyClicksAreHidden() {
    List<ScoredJob> ranked = jobs(3);
    List<UUID> newest = ids(jobs(3));
    givenRanking(ranked);
    when(graphRepository.newestJobs(anyInt())).thenReturn(newest);
    givenContext(List.of(ranked.get(0).jobId(), newest.get(0)));
    givenInteractions(
        interaction(ranked.get(1).jobId(), JobInteractionType.APPLY_CLICKED, Duration.ofDays(13)),
        interaction(newest.get(1), JobInteractionType.APPLY_CLICKED, Duration.ZERO));

    assertThat(ids(service.recommend(USER, 10))).containsExactly(ranked.get(2).jobId(), newest.get(2));
  }

  @Test
  void seenJobsComeLastTheOneSeenLongestAgoFirst() {
    List<ScoredJob> ranked = jobs(4);
    UUID viewed = ranked.get(0).jobId();
    UUID appliedLongAgo = ranked.get(1).jobId();
    givenRanking(ranked);
    givenInteractions(
        interaction(appliedLongAgo, JobInteractionType.APPLY_CLICKED, Duration.ofDays(15)),
        interaction(viewed, JobInteractionType.VIEWED, Duration.ofDays(1)));

    List<UUID> all = ids(service.recommend(USER, 10));

    assertThat(all.subList(0, 2)).containsExactlyInAnyOrder(ranked.get(2).jobId(), ranked.get(3).jobId());
    assertThat(all.subList(2, 4)).containsExactly(appliedLongAgo, viewed);
    assertThat(ids(service.recommend(USER, 2))).doesNotContain(viewed, appliedLongAgo);
  }

  @Test
  void aJobViewedOnTheWayToApplyingStaysHidden() {
    UUID applied = UUID.randomUUID();
    givenRanking(List.of());
    givenInteractions(
        interaction(applied, JobInteractionType.VIEWED, Duration.ofHours(2)),
        interaction(applied, JobInteractionType.APPLY_CLICKED, Duration.ofHours(1)));

    assertThat(ids(service.recommend(USER, 10))).isEmpty();
  }

  @Test
  void preferenceReordersTheRanking() {
    List<ScoredJob> ranked = List.of(
        new ScoredJob(UUID.randomUUID(), 0.5), new ScoredJob(UUID.randomUUID(), 0.4), new ScoredJob(UUID.randomUUID(), 0.3));
    givenRanking(ranked);
    givenContext(List.of(),
        new PreferenceMatch(ranked.get(0).jobId(), null, false, null, null),
        new PreferenceMatch(ranked.get(2).jobId(), true, true, true, null));

    assertThat(ids(service(0).recommend(USER, 10)))
        .containsExactly(ranked.get(2).jobId(), ranked.get(1).jobId(), ranked.get(0).jobId());
  }

  @Test
  void preferenceAlonePersonalizesTheNewestJobs() {
    List<UUID> newest = ids(jobs(3));
    givenRanking(List.of());
    when(graphRepository.newestJobs(anyInt())).thenReturn(newest);
    assertThat(service.recommend(USER, 10).personalized()).isFalse();

    givenContext(List.of(), new PreferenceMatch(newest.get(2), true, true, true, true));

    RecommendedJobsResponse response = service(0).recommend(USER, 10);
    assertThat(response.personalized()).isTrue();
    assertThat(ids(response).get(0)).isEqualTo(newest.get(2));
  }

  @Test
  void copiesOfACardAreShownOnce() {
    List<ScoredJob> ranked = jobs(4);
    givenRanking(ranked);
    doAnswer(call -> ((IdsRequest) call.getArgument(0)).ids().stream()
        .map(id -> new BackendJobSummary(id, id.equals(ranked.get(3).jobId()) ? "Other" : " Same", "Acme", null, null,
            List.of("Hà Nội"), null, null, null)).toList())
        .when(backendClient).findJobSummaries(any());

    assertThat(service.recommend(USER, 10).content()).extracting(BackendJobSummary::title)
        .containsExactlyInAnyOrder(" Same", "Other");
  }

  @Test
  void matchedSkillsAreReadForTheShownJobsOnly() {
    List<ScoredJob> ranked = jobs(3);
    givenRanking(ranked);
    when(graphRepository.matchedSkills(eq(USER), any(), anyInt())).thenAnswer(call ->
        Map.of(((List<UUID>) call.getArgument(1)).get(0), List.of("Java")));

    RecommendedJobsResponse response = service.recommend(USER, 1);

    assertThat(response.matchedSkills()).containsExactly(Map.entry(ids(response).get(0), List.of("Java")));
    verify(graphRepository).matchedSkills(USER, ids(response), 3);
  }

  @Test
  void poolIsToppedUpWithNewJobsAfterThePersonalizedOnes() {
    List<ScoredJob> ranked = jobs(2);
    UUID fresh = UUID.randomUUID();
    givenRanking(ranked);
    when(graphRepository.newestJobs(anyInt())).thenReturn(List.of(ranked.get(0).jobId(), fresh));

    RecommendedJobsResponse response = service.recommend(USER, 10);

    assertThat(ids(response)).hasSize(3).doesNotHaveDuplicates();
    assertThat(ids(response).get(2)).isEqualTo(fresh);
  }

  @Test
  void withoutAProjectionEveryoneGetsTheNewestJobs() {
    when(projectionService.activeGraphName()).thenReturn(Optional.empty());
    List<UUID> newest = ids(jobs(4));
    when(graphRepository.newestJobs(anyInt())).thenReturn(newest);

    RecommendedJobsResponse response = service.recommend(USER, 10);

    assertThat(ids(response)).containsExactlyInAnyOrderElementsOf(newest);
    assertThat(response.personalized()).isFalse();
    verify(graphRepository, never()).personalizedRanking(anyString(), any(), anyInt());
  }

  @Test
  void everyRequestReshufflesTheList() {
    when(graphRepository.personalizedRanking("graph-1", USER, POOL)).thenReturn(jobs(POOL));

    List<UUID> first = ids(service.recommend(USER, POOL));

    boolean reshuffled = IntStream.range(0, 20).anyMatch(i -> !ids(service.recommend(USER, POOL)).equals(first));
    assertThat(reshuffled).isTrue();
  }

  @Test
  void sizeLimitsTheListToPersonalizedJobsFirst() {
    List<ScoredJob> ranked = jobs(4);
    List<UUID> newest = ids(jobs(6));
    when(graphRepository.personalizedRanking("graph-1", USER, POOL)).thenReturn(ranked);
    when(graphRepository.newestJobs(anyInt())).thenReturn(newest);

    assertThat(ids(service.recommend(USER, 4))).containsExactlyInAnyOrderElementsOf(ids(ranked));
  }

  @Test
  void pageRankRunsOncePerProjection() {
    when(graphRepository.personalizedRanking(anyString(), eq(USER), anyInt())).thenReturn(jobs(3));

    service.recommend(USER, 10);
    service.recommend(USER, 10);
    verify(graphRepository, times(1)).personalizedRanking("graph-1", USER, POOL);

    when(projectionService.activeGraphName()).thenReturn(Optional.of("graph-2"));
    service.recommend(USER, 10);
    verify(graphRepository, times(1)).personalizedRanking("graph-2", USER, POOL);
  }

  @Test
  void projectionReplacedMidRequestIsRetriedOnTheNewOne() {
    List<ScoredJob> ranked = jobs(2);
    when(projectionService.activeGraphName()).thenReturn(Optional.of("graph-1"), Optional.of("graph-2"));
    when(graphRepository.personalizedRanking("graph-1", USER, POOL)).thenThrow(procedureFailed());
    when(graphRepository.personalizedRanking("graph-2", USER, POOL)).thenReturn(ranked);

    RecommendedJobsResponse response = service.recommend(USER, 10);

    assertThat(ids(response)).containsExactlyInAnyOrderElementsOf(ids(ranked));
    assertThat(response.personalized()).isTrue();
  }

  @Test
  void userMissingFromTheProjectionGetsTheFallback() {
    List<UUID> newest = ids(jobs(2));
    when(graphRepository.personalizedRanking("graph-1", USER, POOL)).thenThrow(procedureFailed());
    when(graphRepository.newestJobs(anyInt())).thenReturn(newest);

    RecommendedJobsResponse response = service.recommend(USER, 10);

    assertThat(ids(response)).containsExactlyInAnyOrderElementsOf(newest);
    assertThat(response.personalized()).isFalse();
  }

  @Test
  void failedPageRankIsNotCached() {
    List<ScoredJob> ranked = jobs(2);
    when(graphRepository.personalizedRanking("graph-1", USER, POOL)).thenThrow(procedureFailed()).thenReturn(ranked);

    assertThat(service.recommend(USER, 10).personalized()).isFalse();
    assertThat(service.recommend(USER, 10).personalized()).isTrue();
  }

  @Test
  void otherNeo4jErrorsAreNotHiddenBehindTheFallback() {
    ClientException pluginMissing = new ClientException("Neo.ClientError.Procedure.ProcedureNotFound", "no gds");
    when(graphRepository.personalizedRanking("graph-1", USER, POOL)).thenThrow(pluginMissing);

    assertThatThrownBy(() -> service.recommend(USER, 10)).isSameAs(pluginMissing);
  }

  @Test
  void jobsTheBackendNoLongerHasAreReplacedByTheNextOnes() {
    List<ScoredJob> ranked = jobs(3);
    UUID gone = ranked.get(0).jobId();
    when(graphRepository.personalizedRanking("graph-1", USER, POOL)).thenReturn(ranked);
    doAnswer(call -> ((IdsRequest) call.getArgument(0)).ids().stream()
        .filter(id -> !id.equals(gone)).map(RecommendationServiceTest::card).toList())
        .when(backendClient).findJobSummaries(any());

    assertThat(ids(service.recommend(USER, 2)))
        .containsExactlyInAnyOrder(ranked.get(1).jobId(), ranked.get(2).jobId());
  }

  @Test
  void invalidSizeIsRejected() {
    for (int size : new int[] {0, 51}) {
      assertThatThrownBy(() -> service.recommend(USER, size))
          .isInstanceOf(ApiException.class)
          .hasMessage(RecommendationService.INVALID_SIZE);
    }
  }

  private static ClientException procedureFailed() {
    return new ClientException(RecommendationService.PROCEDURE_FAILED, "failed");
  }

  private RecommendationService service(double temperature) {
    return new RecommendationService(graphRepository, projectionService, interactionRepository, backendClient,
        new RecommendationProperties(POOL, 50, temperature, Duration.ofMinutes(15), Duration.ofMinutes(5),
            Duration.ofDays(14), 20, 0.85));
  }

  private void givenRanking(List<ScoredJob> ranked) {
    when(graphRepository.personalizedRanking("graph-1", USER, POOL)).thenReturn(ranked);
  }

  private void givenContext(List<UUID> saved, PreferenceMatch... matches) {
    when(backendClient.findRecommendationContext(any()))
        .thenReturn(new BackendRecommendationContext(saved, Arrays.asList(matches)));
  }

  /** Oldest first, as the repository returns them. */
  private void givenInteractions(UserJobInteraction... interactions) {
    when(interactionRepository.findByUserIdOldestFirst(USER)).thenReturn(Arrays.asList(interactions));
  }

  private static UserJobInteraction interaction(UUID jobId, JobInteractionType type, Duration ago) {
    UserJobInteraction interaction = new UserJobInteraction();
    interaction.setId(new UserJobInteraction.UserJobInteractionId(USER, jobId, type));
    interaction.setLastAt(Instant.now().minus(ago));
    return interaction;
  }

  private static List<ScoredJob> jobs(int count) {
    return IntStream.range(0, count)
        .mapToObj(rank -> new ScoredJob(UUID.randomUUID(), 1.0 / (rank + 1)))
        .toList();
  }

  private static List<UUID> ids(List<ScoredJob> jobs) {
    return jobs.stream().map(ScoredJob::jobId).toList();
  }

  private static List<UUID> ids(RecommendedJobsResponse response) {
    return response.content().stream().map(BackendJobSummary::id).toList();
  }

  private static BackendJobSummary card(UUID id) {
    return new BackendJobSummary(id, "Job " + id, "Company", null, null, List.of(), null, null, null);
  }
}
