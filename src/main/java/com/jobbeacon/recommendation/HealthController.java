package com.jobbeacon.recommendation;

import com.jobbeacon.recommendation.cdc.CdcHealth;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class HealthController {

    private final CdcHealth cdcHealth;

    @GetMapping("/api/v1/health")
    public ResponseEntity<Map<String, String>> health() {
        boolean healthy = cdcHealth.isHealthy();
        String status = healthy ? "UP" : "DOWN";
        return ResponseEntity.status(healthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("status", status, "cdc", status));
    }
}
