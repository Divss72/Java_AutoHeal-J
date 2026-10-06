package com.autoheal.gatewayservice;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/gateway")
public class GatewayController {

    private final RateLimitingFilter rateLimitingFilter;
    private final BulkheadIsolationFilter bulkheadIsolationFilter;

    @Autowired
    public GatewayController(RateLimitingFilter rateLimitingFilter, BulkheadIsolationFilter bulkheadIsolationFilter) {
        this.rateLimitingFilter = rateLimitingFilter;
        this.bulkheadIsolationFilter = bulkheadIsolationFilter;
    }

    @GetMapping("/status")
    public String getStatus() {
        return "Gateway is routing traffic correctly.";
    }

    @GetMapping("/resilience/status")
    public ResponseEntity<Map<String, Object>> getResilienceStatus() {
        Map<String, Object> status = new HashMap<>();

        Map<String, Object> rateLimitData = new HashMap<>();
        rateLimitData.put("enabled", rateLimitingFilter.isEnabled());
        rateLimitData.put("capacity", rateLimitingFilter.getCapacity());
        rateLimitData.put("refillRate", rateLimitingFilter.getRefillRate());
        rateLimitData.put("activeClientBuckets", rateLimitingFilter.getActiveClientBucketCount());
        status.put("rateLimiter", rateLimitData);

        Map<String, Object> bulkheadData = new HashMap<>();
        bulkheadData.put("enabled", bulkheadIsolationFilter.isEnabled());
        bulkheadData.put("maxConcurrentCalls", bulkheadIsolationFilter.getMaxConcurrentCalls());
        bulkheadData.put("maxWaitDurationMs", bulkheadIsolationFilter.getMaxWaitDurationMs());
        bulkheadData.put("services", bulkheadIsolationFilter.getBulkheadMetrics());
        status.put("bulkhead", bulkheadData);

        return ResponseEntity.ok(status);
    }
}
