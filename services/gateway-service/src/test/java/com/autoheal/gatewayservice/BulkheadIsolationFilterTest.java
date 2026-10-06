package com.autoheal.gatewayservice;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BulkheadIsolationFilterTest {

    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        filterChain = mock(FilterChain.class);
    }

    @Test
    void testResolveTargetService() {
        BulkheadIsolationFilter filter = new BulkheadIsolationFilter(true, 5, 100);

        assertEquals("user-service", filter.resolveTargetService("/api/users/profile"));
        assertEquals("user-service", filter.resolveTargetService("/api/users"));
        assertEquals("order-service", filter.resolveTargetService("/api/orders/1234"));
        assertEquals("order-service", filter.resolveTargetService("/api/orders"));
        assertEquals("payment-service", filter.resolveTargetService("/api/payments/pay"));
        assertEquals("payment-service", filter.resolveTargetService("/api/payments"));
        assertNull(filter.resolveTargetService("/actuator/health"));
        assertNull(filter.resolveTargetService("/api/gateway/status"));
        assertNull(filter.resolveTargetService("/simulate/cpu"));
    }

    @Test
    void testSequentialRequestsWithinCapacity_Succeed() throws ServletException, IOException {
        BulkheadIsolationFilter filter = new BulkheadIsolationFilter(true, 2, 100);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/list");
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, filterChain);
            assertEquals(200, response.getStatus());
        }

        verify(filterChain, times(5)).doFilter(any(), any());
    }

    @Test
    void testBulkheadRejection_WhenConcurrentCapacityExceeded() throws Exception {
        // Bulkhead capacity = 2, wait timeout = 50ms
        BulkheadIsolationFilter filter = new BulkheadIsolationFilter(true, 2, 50);

        CountDownLatch inFlightLatch = new CountDownLatch(2);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(4);

        FilterChain slowFilterChain = (req, res) -> {
            inFlightLatch.countDown();
            try {
                // Hold permit until test releases it
                releaseLatch.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        // Submit 2 slow requests that hold the bulkhead permits
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/orders/create");
                MockHttpServletResponse res = new MockHttpServletResponse();
                try {
                    filter.doFilterInternal(req, res, slowFilterChain);
                } catch (Exception ignored) {
                }
            });
        }

        // Wait until both slow requests are holding permits
        assertTrue(inFlightLatch.await(2, TimeUnit.SECONDS));

        // 3rd concurrent request should time out waiting for bulkhead permit and return 503
        MockHttpServletRequest overflowRequest = new MockHttpServletRequest("GET", "/api/orders/status");
        MockHttpServletResponse overflowResponse = new MockHttpServletResponse();
        filter.doFilterInternal(overflowRequest, overflowResponse, filterChain);

        assertEquals(503, overflowResponse.getStatus());
        assertEquals("1", overflowResponse.getHeader("Retry-After"));
        assertEquals("order-service", overflowResponse.getHeader("X-AutoHeal-Bulkhead-Rejected"));
        assertTrue(overflowResponse.getContentAsString().contains("Bulkhead concurrency limit (2) reached"));

        // Release the held permits
        releaseLatch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));

        // After releasing, a new request should be accepted
        MockHttpServletResponse subsequentResponse = new MockHttpServletResponse();
        filter.doFilterInternal(overflowRequest, subsequentResponse, filterChain);
        assertEquals(200, subsequentResponse.getStatus());
    }

    @Test
    void testNonRoutedEndpoints_BypassBulkhead() throws ServletException, IOException {
        BulkheadIsolationFilter filter = new BulkheadIsolationFilter(true, 1, 50);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/gateway/status");
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, filterChain);
            assertEquals(200, response.getStatus());
        }

        verify(filterChain, times(5)).doFilter(any(), any());
    }

    @Test
    void testDisabledBulkhead_AllowsAllRequests() throws ServletException, IOException {
        BulkheadIsolationFilter filter = new BulkheadIsolationFilter(false, 1, 50);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/profile");
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, filterChain);
            assertEquals(200, response.getStatus());
        }

        verify(filterChain, times(5)).doFilter(any(), any());
    }
}
