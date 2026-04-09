package com.autoheal.dashboard;

import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class DashboardData {

    private final List<Anomaly> anomalies = new CopyOnWriteArrayList<>();
    private final List<HealingEvent> healingEvents = new CopyOnWriteArrayList<>();

    private double currentCpu = 0.0;
    private double currentMem = 0.0;
    private double currentLatency = 0.0;
    private double currentErrorRate = 0.0;
    private double currentRps = 0.0;

    private int detectionTimeSec = 0;
    private int avgRecoveryTimeSec = 0;
    private int totalHealed = 0;
    private int totalAttempted = 0;

    // Per-service status metrics
    private final Map<String, ServiceStatus> serviceStatuses = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        serviceStatuses.put("user-service", new ServiceStatus("user-service"));
        serviceStatuses.put("order-service", new ServiceStatus("order-service"));
        serviceStatuses.put("payment-service", new ServiceStatus("payment-service"));
        serviceStatuses.put("gateway-service", new ServiceStatus("gateway-service"));
    }

    public Map<String, ServiceStatus> getServiceStatuses() {
        return serviceStatuses;
    }

    public void addAnomaly(String service, String metrics, double score) {
        String timestamp = LocalDateTime.now().atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString();
        anomalies.add(0, new Anomaly(timestamp, service, score, metrics));
        if (anomalies.size() > 50) anomalies.remove(anomalies.size() - 1);
        detectionTimeSec = 5; // Simulating average detection time
        
        ServiceStatus svc = serviceStatuses.get(service);
        if (svc != null) {
            svc.setStatus("DEGRADED");
        }
    }

    public void addHealingEvent(String service, String action, boolean success, String aiInsight) {
        String timestamp = LocalDateTime.now().atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString();
        String result = success ? "SUCCESS" : "FAILED";
        healingEvents.add(0, new HealingEvent(timestamp, service, action, result, aiInsight));
        if (healingEvents.size() > 50) healingEvents.remove(healingEvents.size() - 1);

        totalAttempted++;
        if (success) {
            totalHealed++;
            avgRecoveryTimeSec = 18; // Defaulting to approx recovery time if successful
            
            ServiceStatus svc = serviceStatuses.get(service);
            if (svc != null) {
                svc.setStatus("RECOVERING");
                // Reset its metrics
                svc.setCpu(Math.random() * 20.0 + 10.0);
                svc.setMemory(Math.random() * 100.0 + 50.0);
                svc.setErrorRate(0.0);
                svc.setLatency(Math.random() * 0.1 + 0.05);
                
                // Automatically transition back to HEALTHY after 5 seconds to clear the DEGRADED system state
                new Thread(() -> {
                    try {
                        Thread.sleep(5000);
                        if ("RECOVERING".equals(svc.getStatus())) {
                            svc.setStatus("HEALTHY");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            }
        }
    }

    // Getters and Setters
    public List<Anomaly> getAnomalies() { return anomalies; }
    public List<HealingEvent> getHealingEvents() { return healingEvents; }

    public double getCurrentCpu() { return currentCpu; }
    public void setCurrentCpu(double currentCpu) { this.currentCpu = currentCpu; }

    public double getCurrentMem() { return currentMem; }
    public void setCurrentMem(double currentMem) { this.currentMem = currentMem; }

    public double getCurrentLatency() { return currentLatency; }
    public void setCurrentLatency(double currentLatency) { this.currentLatency = currentLatency; }

    public double getCurrentErrorRate() { return currentErrorRate; }
    public void setCurrentErrorRate(double currentErrorRate) { this.currentErrorRate = currentErrorRate; }

    public double getCurrentRps() { return currentRps; }
    public void setCurrentRps(double currentRps) { this.currentRps = currentRps; }

    public int getDetectionTimeSec() { return detectionTimeSec; }
    public int getAvgRecoveryTimeSec() { return avgRecoveryTimeSec; }
    
    public double getSuccessRate() {
        if (totalAttempted == 0) return 100.0;
        return ((double) totalHealed / totalAttempted) * 100.0;
    }

    public double getAvailability() {
        if (currentErrorRate > 0) {
            return Math.max(0.0, 100.0 - (currentErrorRate * 20));
        }
        return 100.0;
    }

    public static class ServiceStatus {
        private String name;
        private String status;
        private double cpu;
        private double memory;
        private double latency;
        private double errorRate;

        public ServiceStatus(String name) {
            this.name = name;
            this.status = "HEALTHY";
            this.cpu = Math.random() * 20.0 + 10.0;
            this.memory = Math.random() * 100.0 + 50.0;
            this.latency = Math.random() * 0.1 + 0.05;
            this.errorRate = 0.0;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public double getCpu() { return cpu; }
        public void setCpu(double cpu) { this.cpu = cpu; }
        public double getMemory() { return memory; }
        public void setMemory(double memory) { this.memory = memory; }
        public double getLatency() { return latency; }
        public void setLatency(double latency) { this.latency = latency; }
        public double getErrorRate() { return errorRate; }
        public void setErrorRate(double errorRate) { this.errorRate = errorRate; }
    }

    public static class Anomaly {
        private final String timestamp;
        private final String service;
        private final double score;
        private final String metrics;

        public Anomaly(String timestamp, String service, double score, String metrics) {
            this.timestamp = timestamp;
            this.service = service;
            this.score = score;
            this.metrics = metrics;
        }

        public String getTimestamp() { return timestamp; }
        public String getService() { return service; }
        public double getScore() { return score; }
        public String getMetrics() { return metrics; }
    }

    public static class HealingEvent {
        private final String timestamp;
        private final String service;
        private final String action;
        private final String result;
        private final String aiInsight;

        public HealingEvent(String timestamp, String service, String action, String result, String aiInsight) {
            this.timestamp = timestamp;
            this.service = service;
            this.action = action;
            this.result = result;
            this.aiInsight = aiInsight;
        }

        public String getTimestamp() { return timestamp; }
        public String getService() { return service; }
        public String getAction() { return action; }
        public String getResult() { return result; }
        public String getAiInsight() { return aiInsight; }
    }
}

