package com.autoheal.dashboard;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import org.springframework.web.client.RestTemplate;

@Controller
public class DashboardController {

    @Autowired
    private DashboardData dashboardData;

    @Autowired
    private OpenAIService openAIService;

    // --- UI Routing ---
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("data", dashboardData);
        return "dashboard";
    }

    // --- Webhooks for AutoHeal Engines ---
    
    @PostMapping("/api/webhook/anomaly")
    @ResponseBody
    public ResponseEntity<String> receiveAnomaly(@RequestBody Map<String, Object> payload) {
        String service = (String) payload.getOrDefault("service", "unknown");
        double score = payload.containsKey("score") ? Double.parseDouble(payload.get("score").toString()) : 1.0;
        String metrics = payload.containsKey("metrics") ? payload.get("metrics").toString() : "";
        
        dashboardData.addAnomaly(service, metrics, score);
        
        // Spike the graphs for visual effect since we received an anomaly!
        dashboardData.setCurrentErrorRate(0.8);
        dashboardData.setCurrentCpu(0.95);
        dashboardData.setCurrentLatency(1.2);
        
        return ResponseEntity.ok("Anomaly recorded");
    }

    @PostMapping("/api/webhook/healing")
    @ResponseBody
    public ResponseEntity<String> receiveHealing(@RequestBody Map<String, Object> payload) {
        String service = (String) payload.getOrDefault("service", "unknown");
        String action = (String) payload.getOrDefault("action", "unknown action");
        boolean success = Boolean.parseBoolean(payload.getOrDefault("success", "false").toString());

        // Get AI Insight in background
        String insight = "Fetching...";
        new Thread(() -> {
            String aiResult = openAIService.getInsight(service, action);
            // Replace insight in the latest event
            if (!dashboardData.getHealingEvents().isEmpty()) {
                DashboardData.HealingEvent last = dashboardData.getHealingEvents().get(0);
                dashboardData.getHealingEvents().set(0, new DashboardData.HealingEvent(
                    last.getTimestamp(), last.getService(), last.getAction(), last.getResult(), aiResult
                ));
            }
        }).start();

        dashboardData.addHealingEvent(service, action, success, insight);
        
        // Drop the metrics back down to normal since healing fired
        if (success) {
            dashboardData.setCurrentErrorRate(0.01);
            dashboardData.setCurrentCpu(0.35);
            dashboardData.setCurrentLatency(0.05);
        }

        return ResponseEntity.ok("Healing recorded");
    }

    // --- Data Endpoint for AJAX Reloading ---
    @GetMapping("/api/status")
    @ResponseBody
    public DashboardData getStatus() {
        return dashboardData;
    }

    // --- Advanced Control Panel Endpoints ---

    @GetMapping("/api/services")
    @ResponseBody
    public Map<String, DashboardData.ServiceStatus> getServices() {
        return dashboardData.getServiceStatuses();
    }

    @org.springframework.beans.factory.annotation.Value("${autoheal.security.admin-token:autoheal-secure-admin-token}")
    private String adminToken;

    @PostMapping("/api/control/{action}/{service}")
    @ResponseBody
    public ResponseEntity<String> controlService(@PathVariable String action, @PathVariable String service) {
        String url = getServiceUrl(service) + "/simulate/" + action;
        
        // Spike visual metrics locally
        DashboardData.ServiceStatus svc = dashboardData.getServiceStatuses().get(service);
        if (svc != null) {
            if ("crash".equals(action) || "stop".equals(action)) {
                svc.setStatus("CRASHED");
                svc.setCpu(0); svc.setMemory(0);
                url = getServiceUrl(service) + "/simulate/crash"; // map stop to crash
            } else if ("cpu".equals(action)) {
                svc.setCpu(99.9);
                svc.setStatus("DEGRADED");
            } else if ("memory".equals(action)) {
                svc.setMemory(svc.getMemory() + 50);
                svc.setStatus("DEGRADED");
            } else if ("latency".equals(action)) {
                svc.setLatency(5.0);
                svc.setStatus("DEGRADED");
            } else if ("restart".equals(action)) {
                // If we want a clean restart, we can just crash it to let the system self-heal
                svc.setStatus("CRASHED");
                svc.setCpu(0); svc.setMemory(0);
                url = getServiceUrl(service) + "/simulate/crash";
            }
        }
        
        try {
            RestTemplate restTemplate = new RestTemplate();
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("X-AutoHeal-Admin-Token", adminToken);
            org.springframework.http.HttpEntity<Void> requestEntity = new org.springframework.http.HttpEntity<>(headers);
            restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, requestEntity, String.class);
            return ResponseEntity.ok("Simulated " + action + " on " + service);
        } catch (Exception e) {
            // JVM crashes usually return an error or EOF because the connection is dropped.
            if (e.getMessage() != null && (e.getMessage().contains("I/O error") || e.getMessage().contains("Connection refused") || e.getMessage().contains("EOF"))) {
                return ResponseEntity.ok("Service " + service + " successfully crashed/stopped.");
            }
            return ResponseEntity.status(500).body("Error executing " + action + " on " + service + ": " + e.getMessage());
        }
    }

    private String getServiceUrl(String service) {
        if (System.getenv("KUBERNETES_SERVICE_HOST") != null) {
            switch (service) {
                case "user-service": return "http://user-service:8081";
                case "order-service": return "http://order-service:8082";
                case "payment-service": return "http://payment-service:8083";
                case "gateway-service": return "http://gateway-service:8080";
                default: return "http://" + service;
            }
        }
        switch (service) {
            case "user-service": return "http://localhost:8081";
            case "order-service": return "http://localhost:8082";
            case "payment-service": return "http://localhost:8083";
            case "gateway-service": return "http://localhost:8080";
            default: return "http://localhost:8080";
        }
    }
}
