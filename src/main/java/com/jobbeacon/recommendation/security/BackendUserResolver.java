package com.jobbeacon.recommendation.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jobbeacon.recommendation.backend.BackendClient;
import com.jobbeacon.recommendation.web.ApiException;
import feign.FeignException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Component
public class BackendUserResolver {

  public static final String USER_ID_HEADER = "X-User-Id";
  static final String UNAUTHORIZED = "unauthorized";

  private final BackendClient backendClient;
  private final Cache<String, UUID> userIds = Caffeine.newBuilder()
      .maximumSize(10_000)
      .expireAfterAccess(Duration.ofHours(12))
      .build();

  public BackendUserResolver(BackendClient backendClient) {
    this.backendClient = backendClient;
  }

  public UUID requireUserId(String firebaseUid) {
    return resolve(firebaseUid).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED));
  }

  public Optional<UUID> resolve(String firebaseUid) {
    UUID cached = userIds.getIfPresent(firebaseUid);
    if (cached != null) {
      return Optional.of(cached);
    }
    try {
      UUID userId = backendClient.findUserByFirebaseUid(firebaseUid).id();
      userIds.put(firebaseUid, userId);
      return Optional.of(userId);
    } catch (FeignException.NotFound e) {
      return Optional.empty();
    }
  }
}
