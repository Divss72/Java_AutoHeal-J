package com.autoheal.gatewayservice;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bulkhead Isolation Filter.
 * Partitions gateway resources by downstream target microservice
 * to prevent slow or degraded downstream nodes (e.g. suffering thread leaks,
 * high latency, or database deadlocks) from consuming all gateway worker threads
 * and causing cascading cluster collapse.
 */
@Component
@Order(3)
public class BulkheadIsolationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BulkheadIsolationFilter.class);

    @Value("${autoheal.bulkhead.enabled:true}")
    private boolean enabled;

    @Value("${autoheal.bulkhead.max-concurrent-calls:10}")
    private int maxConcurrentCalls;

    @Value("${autoheal.bulkhead.max-wait-duration-ms:200}")
    private long maxWaitDurationMs;

    private final Map<String, ServiceBulkhead> bulkheads = new ConcurrentHashMap<>();

    public BulkheadIsolationFilter() {
    }

    // Constructor for testing
    public BulkheadIsolationFilter(boolean enabled, int maxConcurrentCalls, long maxWaitDurationMs) {
        this.enabled = enabled;
        this.maxConcurrentCalls = maxConcurrentCalls;
        this.maxWaitDurationMs = maxWaitDurationMs;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        String targetService = resolveTargetService(request.getRequestURI());
        if (targetService == null) {
            // Not a routed downstream microservice request (e.g. gateway self status, actuator)
            filterChain.doFilter(request, response);
            return;
        }

        ServiceBulkhead bulkhead = bulkheads.computeIfAbsent(
                targetService,
                k -> new ServiceBulkhead(k, maxConcurrentCalls)
        );

        boolean acquired = false;
        try {
            acquired = bulkhead.acquire(maxWaitDurationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Thread interrupted while waiting for bulkhead permit for service: {}", targetService);
        }

        if (!acquired) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setHeader("Retry-After", "1");
            response.setHeader("X-AutoHeal-Bulkhead-Rejected", targetService);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);

            String errorJson = String.format(
                    "{\"error\":\"Service Unavailable\",\"message\":\"Bulkhead concurrency limit (%d) reached for downstream service '%s'. Request rejected to prevent cascading thread exhaustion.\"}",
                    maxConcurrentCalls, targetService
            );
            response.getWriter().write(errorJson);
            log.warn("Bulkhead rejected request for downstream service '{}'. In-flight capacity saturated.", targetService);
            return;
        }

        try {
            bulkhead.markCallStarted();
            filterChain.doFilter(request, response);
        } finally {
            bulkhead.markCallFinished();
            bulkhead.release();
        }
    }

    /**
     * Maps an incoming path to its downstream target service.
     */
    public String resolveTargetService(String path) {
        if (path == null) {
            return null;
        }
        if (path.startsWith("/api/users/") || path.equals("/api/users")) {
            return "user-service";
        }
        if (path.startsWith("/api/orders/") || path.equals("/api/orders")) {
            return "order-service";
        }
        if (path.startsWith("/api/payments/") || path.equals("/api/payments")) {
            return "payment-service";
        }
        return null;
    }

    public Map<String, Map<String, Object>> getBulkheadMetrics() {
        Map<String, Map<String, Object>> metrics = new HashMap<>();
        for (Map.Entry<String, ServiceBulkhead> entry : bulkheads.entrySet()) {
            ServiceBulkhead b = entry.getValue();
            Map<String, Object> data = new HashMap<>();
            data.put("maxConcurrentCalls", b.getMaxCapacity());
            data.put("activeCalls", b.getActiveCalls());
            data.put("availablePermits", b.getAvailablePermits());
            metrics.put(entry.getKey(), data);
        }
        return Collections.unmodifiableMap(metrics);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxConcurrentCalls() {
        return maxConcurrentCalls;
    }

    public long getMaxWaitDurationMs() {
        return maxWaitDurationMs;
    }

    /**
     * Thread-safe bulkhead state encapsulation using fair Semaphores.
     */
    public static class ServiceBulkhead {
        private final String serviceName;
        private final int maxCapacity;
        private final Semaphore semaphore;
        private final AtomicInteger activeCalls = new AtomicInteger(0);

        public ServiceBulkhead(String serviceName, int maxCapacity) {
            this.serviceName = serviceName;
            this.maxCapacity = maxCapacity;
            this.semaphore = new Semaphore(maxCapacity, true);
        }

        public boolean acquire(long timeoutMs) throws InterruptedException {
            if (timeoutMs <= 0) {
                return semaphore.tryAcquire();
            }
            return semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        }

        public void release() {
            semaphore.release();
        }

        public void markCallStarted() {
            activeCalls.incrementAndGet();
        }

        public void markCallFinished() {
            activeCalls.decrementAndGet();
        }

        public String getServiceName() {
            return serviceName;
        }

        public int getMaxCapacity() {
            return maxCapacity;
        }

        public int getActiveCalls() {
            return Math.max(0, activeCalls.get());
        }

        public int getAvailablePermits() {
            return semaphore.availablePermits();
        }
    }
}
