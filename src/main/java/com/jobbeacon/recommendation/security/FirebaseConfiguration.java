package com.jobbeacon.recommendation.security;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;

/** Same Firebase project as the backend: this service verifies the frontend's ID tokens itself. */
@Slf4j
@Configuration
public class FirebaseConfiguration {

  @Value("${firebase.credentials-path:firebase-service-account.json}")
  private String credentialsPath;

  @Bean
  public FirebaseApp firebaseApp() throws IOException {
    if (!FirebaseApp.getApps().isEmpty()) {
      return FirebaseApp.getInstance();
    }
    try (InputStream serviceAccount = resolveCredentials().getInputStream()) {
      FirebaseOptions options = FirebaseOptions.builder()
          .setCredentials(GoogleCredentials.fromStream(serviceAccount))
          .build();
      log.info("Firebase initialized from {}", credentialsPath);
      return FirebaseApp.initializeApp(options);
    }
  }

  @Bean
  public FirebaseAuth firebaseAuth(FirebaseApp firebaseApp) {
    return FirebaseAuth.getInstance(firebaseApp);
  }

  private Resource resolveCredentials() {
    Resource classpath = new ClassPathResource(credentialsPath);
    if (classpath.exists()) {
      return classpath;
    }
    Resource file = new FileSystemResource(credentialsPath);
    if (file.exists()) {
      return file;
    }
    throw new IllegalStateException("Firebase credentials file not found: " + credentialsPath
        + ". Place it in the classpath or set FIREBASE_CREDENTIALS_PATH");
  }
}
