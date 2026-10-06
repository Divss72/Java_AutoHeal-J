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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-Bucket Rate Limiter Filter.
 * Protects the gateway and downstream services from volumetric traffic floods,
 * DDoS spikes, and brute-force abuse.
 *
 * Each client (identified by IP or forwarded IP) receives a bucket of tokens.
 * Burst requests are allowed up to 'capacity', refilling at 'refillRate' tokens per second.
 * When exhausted, returns HTTP 429 Too Many Requests with RFC rate limit headers.
 */
@Component
@Order(2)
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    @Value("${autoheal.ratelimit.enabled:true}")
    private boolean enabled;

    @Value("${autoheal.ratelimit.capacity:20}")
    private double capacity;

    @Value("${autoheal.ratelimit.refill-rate:10}")
    private double refillRate;

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    // Timestamp for periodic cleanup of stale buckets (older than 10 minutes)
    private long lastCleanupTimestamp = System.currentTimeMillis();
    private static final long CLEANUP_INTERVAL_MS = 600_000L; // 10 minutes
    private static final long INACTIVE_THRESHOLD_MS = 600_000L;

    public RateLimitingFilter() {
    }

    // Constructor for testing
    public RateLimitingFilter(boolean enabled, double capacity, double refillRate) {
        this.enabled = enabled;
        this.capacity = capacity;
        this.refillRate = refillRate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();

        // Whitelist internal monitoring and health endpoints from rate limiting
        if (isWhitelisted(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientKey = resolveClientKey(request);
        maybeCleanupStaleBuckets();

        TokenBucket bucket = buckets.computeIfAbsent(clientKey, k -> new TokenBucket(capacity, refillRate));

        ConsumptionResult result = bucket.tryConsume(1.0);

        // Attach standard RFC / GitHub style rate limit headers
        response.setHeader("X-RateLimit-Limit", String.valueOf((long) capacity));
        response.setHeader("X-RateLimit-Remaining", String.valueOf((long) Math.max(0, result.remainingTokens)));

        if (!result.consumed) {
            long retryAfterSeconds = Math.max(1, (long) Math.ceil(result.secondsUntilAvailable));
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setHeader("X-RateLimit-Reset", String.valueOf(retryAfterSeconds));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);

            String errorJson = String.format(
                    "{\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Maximum allowed burst is %d requests with a refill rate of %d req/s. Try again in %d second(s).\"}",
                    (long) capacity, (long) refillRate, retryAfterSeconds
            );
            response.getWriter().write(errorJson);
            log.warn("Rate limit exceeded for client: {} on path: {}", clientKey, path);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isWhitelisted(String path) {
        return path.startsWith("/actuator/")
                || path.equals("/actuator")
                || path.equals("/api/gateway/status")
                || path.equals("/api/gateway/resilience/status");
    }

    private String resolveClientKey(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String remoteAddr = request.getRemoteAddr();
        return (remoteAddr != null && !remoteAddr.isBlank()) ? remoteAddr : "unknown-client";
    }

    private synchronized void maybeCleanupStaleBuckets() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupTimestamp > CLEANUP_INTERVAL_MS) {
            buckets.entrySet().removeIf(entry -> (now - entry.getValue().getLastRefillTime()) > INACTIVE_THRESHOLD_MS);
            lastCleanupTimestamp = now;
        }
    }

    public int getActiveClientBucketCount() {
        return buckets.size();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double getCapacity() {
        return capacity;
    }

    public double getRefillRate() {
        return refillRate;
    }

    /**
     * Inner Token Bucket model supporting high-concurrency token refill & consumption.
     */
    public static class TokenBucket {
        private final double maxCapacity;
        private final double tokensPerSecond;
        private double availableTokens;
        private long lastRefillTimestamp;

        public TokenBucket(double maxCapacity, double tokensPerSecond) {
            this.maxCapacity = maxCapacity;
            this.tokensPerSecond = tokensPerSecond;
            this.availableTokens = maxCapacity;
            this.lastRefillTimestamp = System.currentTimeMillis();
        }

        public synchronized ConsumptionResult tryConsume(double tokensRequested) {
            refill();

            if (availableTokens >= tokensRequested) {
                availableTokens -= tokensRequested;
                return new ConsumptionResult(true, availableTokens, 0.0);
            } else {
                double tokensNeeded = tokensRequested - availableTokens;
                double secondsUntilAvailable = tokensPerSecond > 0 ? (tokensNeeded / tokensPerSecond) : 1.0;
                return new ConsumptionResult(false, availableTokens, secondsUntilAvailable);
            }
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long elapsedMs = now - lastRefillTimestamp;
            if (elapsedMs > 0) {
                double tokensToAdd = (elapsedMs / 1000.0) * tokensPerSecond;
                this.availableTokens = Math.min(maxCapacity, this.availableTokens + tokensToAdd);
                this.lastRefillTimestamp = now;
            }
        }

        public synchronized long getLastRefillTime() {
            return lastRefillTimestamp;
        }

        public synchronized double getAvailableTokens() {
            refill();
            return availableTokens;
        }
    }

    public static class ConsumptionResult {
        public final boolean consumed;
        public final double remainingTokens;
        public final double secondsUntilAvailable;

        public ConsumptionResult(boolean consumed, double remainingTokens, double secondsUntilAvailable) {
            this.consumed = consumed;
            this.remainingTokens = remainingTokens;
            this.secondsUntilAvailable = secondsUntilAvailable;
        }
    }
}
