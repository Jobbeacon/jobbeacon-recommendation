package com.jobbeacon.recommendation.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jobbeacon.recommendation.backend.BackendClient;
import feign.FeignException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Maps a Firebase uid to the backend's user id. The mapping never changes once a user exists, so it is cached and the
 * backend is called about once per user rather than on every request.
 */
@Component
public class BackendUserResolver {

  private final BackendClient backendClient;
  private final Cache<String, UUID> userIds = Caffeine.newBuilder()
      .maximumSize(10_000)
      .expireAfterAccess(Duration.ofHours(12))
      .build();

  public BackendUserResolver(BackendClient backendClient) {
    this.backendClient = backendClient;
  }

  /**
   * Empty when the backend has no such user yet (it creates users on their first authenticated backend call). Other
   * backend failures propagate, so an outage is not mistaken for an unknown user.
   */
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
