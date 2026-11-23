package com.jobbeacon.recommendation.recommend;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobbeacon.recommendation.backend.dto.BackendJobSummary;
import com.jobbeacon.recommendation.security.BackendUserResolver;
import com.jobbeacon.recommendation.web.MessageSourceConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RecommendationController.class)
@Import(MessageSourceConfiguration.class)
class RecommendationControllerTest {

  private static final UUID USER = UUID.randomUUID();
  private static final UUID JOB = UUID.randomUUID();

  @Autowired
  private MockMvc mvc;
  @MockBean
  private BackendUserResolver backendUserResolver;
  @MockBean
  private RecommendationService recommendationService;

  @Test
  void returnsJobsOfTheBackendUserInSnakeCase() throws Exception {
    when(backendUserResolver.requireUserId("firebase-uid")).thenReturn(USER);
    BackendJobSummary card = new BackendJobSummary(JOB, "Java Developer", "Acme", 1000L, 2000L, List.of("Hà Nội"),
        "USD", "https://logo", null);
    when(recommendationService.recommend(USER, 5))
        .thenReturn(new RecommendedJobsResponse(List.of(card), true, Map.of(JOB, List.of("Java"))));

    mvc.perform(get("/api/v1/me/recommended-jobs?size=5").header("X-User-Id", "firebase-uid"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].id").value(JOB.toString()))
        .andExpect(jsonPath("$.data.content[0].company_name").value("Acme"))
        .andExpect(jsonPath("$.data.personalized").value(true))
        .andExpect(jsonPath("$.data.matched_skills['" + JOB + "'][0]").value("Java"));
  }

  @Test
  void defaultsToTwentyJobs() throws Exception {
    when(backendUserResolver.requireUserId("firebase-uid")).thenReturn(USER);
    when(recommendationService.recommend(USER, 20))
        .thenReturn(new RecommendedJobsResponse(List.of(), false, Map.of()));

    mvc.perform(get("/api/v1/me/recommended-jobs").header("X-User-Id", "firebase-uid"))
        .andExpect(status().isOk());

    verify(recommendationService).recommend(USER, 20);
  }

  @Test
  void missingUserHeaderIsUnauthorized() throws Exception {
    mvc.perform(get("/api/v1/me/recommended-jobs"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.data.code").value("unauthorized"));

    verifyNoInteractions(recommendationService);
  }

  @Test
  void nonNumericSizeIsABadRequest() throws Exception {
    when(backendUserResolver.requireUserId("firebase-uid")).thenReturn(USER);

    mvc.perform(get("/api/v1/me/recommended-jobs?size=abc").header("X-User-Id", "firebase-uid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.data.code").value("bad_request"));
  }

  @Test
  void neo4jOutageIsServiceUnavailable() throws Exception {
    when(backendUserResolver.requireUserId("firebase-uid")).thenReturn(USER);
    when(recommendationService.recommend(any(), anyInt()))
        .thenThrow(new ServiceUnavailableException("down"));

    mvc.perform(get("/api/v1/me/recommended-jobs").header("X-User-Id", "firebase-uid"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.data.code").value("recommendation_unavailable"));
  }
}
