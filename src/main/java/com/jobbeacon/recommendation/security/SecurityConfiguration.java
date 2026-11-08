package com.jobbeacon.recommendation.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseAuth;
import com.jobbeacon.recommendation.web.ErrorBody;
import com.jobbeacon.recommendation.web.ResponseGeneral;
import feign.FeignException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.LocaleResolver;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

  private final ObjectMapper objectMapper;
  private final MessageSource messageSource;
  private final LocaleResolver localeResolver;

  @Value("${app.cors.allowed-origins:http://localhost:5173}")
  private String allowedOrigins;

  public SecurityConfiguration(ObjectMapper objectMapper, MessageSource messageSource, LocaleResolver localeResolver) {
    this.objectMapper = objectMapper;
    this.messageSource = messageSource;
    this.localeResolver = localeResolver;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http, FirebaseAuth firebaseAuth, BackendUserResolver backendUserResolver
  ) throws Exception {
    FirebaseTokenFilter firebaseTokenFilter = new FirebaseTokenFilter(firebaseAuth, backendUserResolver);
    http.csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health", "/api/v1/health").permitAll()
            .anyRequest().authenticated())
        .exceptionHandling(handling -> handling.authenticationEntryPoint((request, response, exception) ->
            writeError(request, response, HttpStatus.UNAUTHORIZED, "unauthorized")))
        .addFilterBefore(firebaseTokenFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(new BackendOutageFilter(), FirebaseTokenFilter.class);
    return http.build();
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("*"));
    configuration.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  private void writeError(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code)
      throws IOException {
    String detail = messageSource.getMessage(code, null, code, localeResolver.resolveLocale(request));
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(response.getOutputStream(),
        ResponseGeneral.of(status.value(), status.getReasonPhrase(), new ErrorBody(code, detail)));
  }

  /** A backend outage while resolving the user must answer 503, not "please sign in". */
  private class BackendOutageFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      try {
        chain.doFilter(request, response);
      } catch (FeignException e) {
        if (response.isCommitted()) {
          throw e;
        }
        log.error("Backend unavailable while authenticating: {}", e.getMessage());
        writeError(request, response, HttpStatus.SERVICE_UNAVAILABLE, "backend_unavailable");
      }
    }
  }
}
