package com.autoheal.gatewayservice;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateLimitingFilterTest {

    private RateLimitingFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        filterChain = mock(FilterChain.class);
    }

    @Test
    void testRequestsWithinCapacity_Succeed() throws ServletException, IOException {
        // Capacity = 3, Refill = 1 token/sec
        filter = new RateLimitingFilter(true, 3.0, 1.0);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/profile");
        request.setRemoteAddr("192.168.1.100");

        // First 3 requests should succeed
        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, filterChain);

            assertEquals(200, response.getStatus());
            assertEquals("3", response.getHeader("X-RateLimit-Limit"));
            assertNotNull(response.getHeader("X-RateLimit-Remaining"));
        }

        verify(filterChain, times(3)).doFilter(any(), any());
    }

    @Test
    void testRequestExceedingCapacity_Returns429TooManyRequests() throws ServletException, IOException {
        filter = new RateLimitingFilter(true, 2.0, 0.5);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/list");
        request.setRemoteAddr("10.0.0.5");

        // Consume 2 tokens
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, filterChain);
            assertEquals(200, response.getStatus());
        }

        // 3rd request exceeds capacity -> should return 429
        MockHttpServletResponse rejectedResponse = new MockHttpServletResponse();
        filter.doFilterInternal(request, rejectedResponse, filterChain);

        assertEquals(429, rejectedResponse.getStatus());
        assertEquals("2", rejectedResponse.getHeader("X-RateLimit-Limit"));
        assertEquals("0", rejectedResponse.getHeader("X-RateLimit-Remaining"));
        assertNotNull(rejectedResponse.getHeader("Retry-After"));
        assertTrue(rejectedResponse.getContentAsString().contains("Too Many Requests"));

        // filterChain should only have been called for the first 2 requests
        verify(filterChain, times(2)).doFilter(any(), any());
    }

    @Test
    void testWhitelistedEndpoints_BypassRateLimiter() throws ServletException, IOException {
        filter = new RateLimitingFilter(true, 1.0, 0.1);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setRemoteAddr("10.0.0.99");

        // Send 5 requests to whitelisted health endpoint; none should be throttled
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, filterChain);
            assertEquals(200, response.getStatus());
        }

        verify(filterChain, times(5)).doFilter(any(), any());
    }

    @Test
    void testXForwardedForHeader_IsUsedForClientIdentification() throws ServletException, IOException {
        filter = new RateLimitingFilter(true, 2.0, 0.1);

        MockHttpServletRequest client1 = new MockHttpServletRequest("GET", "/api/users/1");
        client1.addHeader("X-Forwarded-For", "203.0.113.195, 70.41.3.18");

        MockHttpServletRequest client2 = new MockHttpServletRequest("GET", "/api/users/1");
        client2.addHeader("X-Forwarded-For", "198.51.100.22");

        // Client 1 consumes 2 tokens
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilterInternal(client1, resp, filterChain);
            assertEquals(200, resp.getStatus());
        }

        // Client 1's 3rd request is blocked
        MockHttpServletResponse respBlocked = new MockHttpServletResponse();
        filter.doFilterInternal(client1, respBlocked, filterChain);
        assertEquals(429, respBlocked.getStatus());

        // Client 2 should NOT be blocked (independent bucket)
        MockHttpServletResponse client2Resp = new MockHttpServletResponse();
        filter.doFilterInternal(client2, client2Resp, filterChain);
        assertEquals(200, client2Resp.getStatus());
    }

    @Test
    void testDisabledFilter_AllowsAllRequests() throws ServletException, IOException {
        filter = new RateLimitingFilter(false, 1.0, 0.1);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/all");
        request.setRemoteAddr("10.0.0.1");

        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, filterChain);
            assertEquals(200, response.getStatus());
        }

        verify(filterChain, times(5)).doFilter(any(), any());
    }
}
