package com.jobbeacon.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.jobbeacon.recommendation.backend.BackendClient;
import com.jobbeacon.recommendation.config.GraphProperties;
import com.jobbeacon.recommendation.graph.GraphRefreshWorker;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Wires the whole application (Feign, security, Liquibase, scheduler) with CDC off and Firebase mocked. */
@SpringBootTest(properties = "app.cdc.enabled=false")
@Testcontainers
class RecommendationApplicationIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @MockBean
  private FirebaseApp firebaseApp;
  @MockBean
  private FirebaseAuth firebaseAuth;

  @Autowired
  private BackendClient backendClient;
  @Autowired
  private GraphRefreshWorker worker;
  @Autowired
  private GraphProperties graphProperties;

  @Test
  void contextStartsWithDefaultConfiguration() {
    assertThat(backendClient).isNotNull();
    assertThat(worker).isNotNull();
    assertThat(graphProperties.interactionWindow()).isEqualTo(Duration.ofDays(180));
    assertThat(graphProperties.batchSize()).isEqualTo(500);
  }
}
