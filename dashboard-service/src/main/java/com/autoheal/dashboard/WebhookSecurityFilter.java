package com.autoheal.dashboard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Order(1)
public class WebhookSecurityFilter extends OncePerRequestFilter {

    @Value("${autoheal.security.webhook-secret:autoheal-webhook-hmac-secret-token}")
    private String webhookSecret;

    // Tolerance window to prevent replay attacks (5 minutes)
    private static final long MAX_AGE_SECONDS = 300;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        if (!path.startsWith("/api/webhook/")) {
            filterChain.doFilter(request, response);
            return;
        }

        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);

        String signatureHeader = cachedRequest.getHeader("X-AutoHeal-Signature");
        String timestampHeader = cachedRequest.getHeader("X-AutoHeal-Timestamp");

        if (signatureHeader == null || timestampHeader == null) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing X-AutoHeal-Signature or X-AutoHeal-Timestamp header.");
            return;
        }

        // Validate timestamp to prevent replay attacks
        try {
            long requestTime = Long.parseLong(timestampHeader);
            long currentTime = System.currentTimeMillis() / 1000L;
            if (Math.abs(currentTime - requestTime) > MAX_AGE_SECONDS) {
                sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Webhook timestamp expired or out of allowed tolerance window.");
                return;
            }
        } catch (NumberFormatException e) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid X-AutoHeal-Timestamp format.");
            return;
        }

        // Calculate expected HMAC-SHA256 signature
        String expectedHash = calculateHmac(timestampHeader, cachedRequest.getCachedBody());
        String expectedSignature = "sha256=" + expectedHash;

        // Constant-time comparison to prevent timing attacks
        if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8), signatureHeader.getBytes(StandardCharsets.UTF_8))) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid HMAC-SHA256 webhook signature.");
            return;
        }

        filterChain.doFilter(cachedRequest, response);
    }

    private String calculateHmac(String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update(".".getBytes(StandardCharsets.UTF_8));
            byte[] rawHmac = mac.doFinal(body);

            StringBuilder hexString = new StringBuilder();
            for (byte b : rawHmac) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate HMAC-SHA256 signature", e);
        }
    }

    private void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\": \"Unauthorized\", \"message\": \"" + message + "\"}");
    }
}
