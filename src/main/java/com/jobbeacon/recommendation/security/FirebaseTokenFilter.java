package com.jobbeacon.recommendation.security;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Authenticates requests carrying a Firebase ID token. The principal is the backend's user id (a {@link UUID}), since
 * that is what interactions and the graph are keyed by. A valid token for a user the backend does not know yet leaves
 * the request unauthenticated.
 *
 * <p>Not a {@code @Component}: it is added to the security chain only, not registered as a servlet filter too.
 */
@Slf4j
public class FirebaseTokenFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  private final FirebaseAuth firebaseAuth;
  private final BackendUserResolver backendUserResolver;

  public FirebaseTokenFilter(FirebaseAuth firebaseAuth, BackendUserResolver backendUserResolver) {
    this.firebaseAuth = firebaseAuth;
    this.backendUserResolver = backendUserResolver;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith(BEARER_PREFIX)) {
      authenticate(header.substring(BEARER_PREFIX.length()));
    }
    filterChain.doFilter(request, response);
  }

  private void authenticate(String token) {
    FirebaseToken firebaseToken;
    try {
      firebaseToken = firebaseAuth.verifyIdToken(token);
    } catch (FirebaseAuthException e) {
      log.warn("Firebase token verification failed: {}", e.getMessage());
      return;
    }
    Optional<UUID> userId = backendUserResolver.resolve(firebaseToken.getUid());
    if (userId.isEmpty()) {
      log.warn("No backend user for Firebase uid {}", firebaseToken.getUid());
      return;
    }
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
        userId.get(), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
  }
}
