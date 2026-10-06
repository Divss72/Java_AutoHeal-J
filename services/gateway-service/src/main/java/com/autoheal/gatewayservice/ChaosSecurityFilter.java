package com.autoheal.gatewayservice;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Security filter that guards chaos and fault injection endpoints (/simulate/**) on the API Gateway.
 * Requires the X-AutoHeal-Admin-Token header and verifies it in constant time
 * to prevent side-channel timing attacks.
 */
@Component
@Order(1)
public class ChaosSecurityFilter extends OncePerRequestFilter {

    @Value("${autoheal.security.admin-token:autoheal-secure-admin-token}")
    private String expectedToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        if (path.startsWith("/simulate/") || path.equals("/simulate")) {
            String incomingToken = request.getHeader("X-AutoHeal-Admin-Token");

            if (incomingToken == null || !MessageDigest.isEqual(
                    expectedToken.getBytes(StandardCharsets.UTF_8),
                    incomingToken.getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                response.setContentType("application/json");
                response.getWriter().write("{\"error\": \"Unauthorized: Missing or invalid X-AutoHeal-Admin-Token\"}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
