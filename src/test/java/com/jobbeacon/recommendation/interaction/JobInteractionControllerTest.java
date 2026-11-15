package com.jobbeacon.recommendation.interaction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobbeacon.recommendation.security.BackendUserResolver;
import com.jobbeacon.recommendation.web.ApiException;
import com.jobbeacon.recommendation.web.MessageSourceConfiguration;
import feign.FeignException;
import feign.Request;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(JobInteractionController.class)
@Import(MessageSourceConfiguration.class)
class JobInteractionControllerTest {

  private static final UUID USER = UUID.randomUUID();
  private static final UUID JOB = UUID.randomUUID();
  private static final String BODY = "{\"job_id\": \"" + JOB + "\", \"type\": \"VIEWED\"}";

  @Autowired
  private MockMvc mvc;
  @MockBean
  private BackendUserResolver backendUserResolver;
  @MockBean
  private JobInteractionService jobInteractionService;

  @Test
  void recordsForTheBackendUserBehindTheGatewayUid() throws Exception {
    when(backendUserResolver.requireUserId("firebase-uid")).thenReturn(USER);

    mvc.perform(interaction("firebase-uid")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value(200));

    verify(jobInteractionService).record(eq(USER), argThat(request ->
        JOB.equals(request.getJobId()) && "VIEWED".equals(request.getType())));
  }

  @Test
  void missingUserHeaderGetsAJsonUnauthorized() throws Exception {
    mvc.perform(post("/api/v1/me/job-interactions")
            .header("Authorization", "Bearer ignored")
            .contentType(MediaType.APPLICATION_JSON)
            .content(BODY))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.data.code").value("unauthorized"));

    verifyNoInteractions(jobInteractionService);
  }

  @Test
  void userUnknownToTheBackendIsUnauthorized() throws Exception {
    when(backendUserResolver.requireUserId(anyString()))
        .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized"));

    mvc.perform(interaction("firebase-uid")).andExpect(status().isUnauthorized());
  }

  @Test
  void backendOutageWhileResolvingTheUserIsServiceUnavailable() throws Exception {
    Request request = Request.create(Request.HttpMethod.GET, "http://backend", Map.of(), null, StandardCharsets.UTF_8,
        null);
    when(backendUserResolver.requireUserId(anyString()))
        .thenThrow(new FeignException.ServiceUnavailable("down", request, null, Map.of()));

    mvc.perform(interaction("firebase-uid"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.data.code").value("backend_unavailable"));
  }

  @Test
  void businessErrorsUseTheBackendEnvelope() throws Exception {
    when(backendUserResolver.requireUserId("firebase-uid")).thenReturn(USER);
    doThrow(ApiException.notFound("job_not_found")).when(jobInteractionService).record(any(), any());

    mvc.perform(interaction("firebase-uid").header("Accept-Language", "vi"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.data.code").value("job_not_found"))
        .andExpect(jsonPath("$.data.detail").value("Không tìm thấy việc làm này hoặc tin đã bị gỡ"));
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder interaction(String uid) {
    return post("/api/v1/me/job-interactions")
        .header("X-User-Id", uid)
        .contentType(MediaType.APPLICATION_JSON)
        .content(BODY);
  }
}
