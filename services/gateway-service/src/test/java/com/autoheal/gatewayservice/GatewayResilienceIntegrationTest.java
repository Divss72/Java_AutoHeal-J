package com.autoheal.gatewayservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class GatewayResilienceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testGatewayStatusEndpoint() throws Exception {
        mockMvc.perform(get("/api/gateway/status"))
                .andExpect(status().isOk());
    }

    @Test
    void testResilienceStatusEndpoint() throws Exception {
        mockMvc.perform(get("/api/gateway/resilience/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rateLimiter.enabled").value(true))
                .andExpect(jsonPath("$.bulkhead.enabled").value(true));
    }

    @Test
    void testChaosEndpoint_WithoutAdminToken_IsUnauthorized() throws Exception {
        mockMvc.perform(get("/simulate/latency"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", containsString("Unauthorized")));
    }

    @Test
    void testChaosEndpoint_WithAdminToken_Succeeds() throws Exception {
        mockMvc.perform(get("/simulate/latency")
                        .header("X-AutoHeal-Admin-Token", "autoheal-secure-admin-token"))
                .andExpect(status().isOk());
    }
}
