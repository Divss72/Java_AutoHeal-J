package com.autoheal.dashboard;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.List;
import java.util.Random;

@Service
public class MetricService {

    @Autowired
    private DashboardData dashboardData;

    @Value("${prometheus.url:http://prometheus:9090}")
    private String prometheusUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final Random random = new Random();

    @Scheduled(fixedRate = 3000)
    public void updateMetrics() {
        try {
            double cpu = fetchMetric("sum(system_cpu_usage) / count(system_cpu_usage)");
            double mem = fetchMetric("sum(jvm_memory_used_bytes) / 1024 / 1024");
            double lat = fetchMetric("sum(rate(http_server_requests_seconds_sum[1m])) / sum(rate(http_server_requests_seconds_count[1m]))");
            double err = fetchMetric("sum(rate(http_server_requests_seconds_count{status=~'5..'}[1m]))");
            double rps = fetchMetric("sum(rate(http_server_requests_seconds_count[1m]))");

            cpu = fetchMetric("sum(system_cpu_usage) / count(system_cpu_usage)");
            mem = fetchMetric("sum(jvm_memory_used_bytes) / 1024 / 1024");
            lat = fetchMetric("sum(rate(http_server_requests_seconds_sum[1m])) / sum(rate(http_server_requests_seconds_count[1m]))");
            err = fetchMetric("sum(rate(http_server_requests_seconds_count{status=~'5..'}[1m]))");
            rps = fetchMetric("sum(rate(http_server_requests_seconds_count[1m]))");

            // Anomaly injection simulation if error rate is high
            if (dashboardData.getCurrentErrorRate() > 0.5) {
                // Keep it high until healed
            } else {
                dashboardData.setCurrentErrorRate(err);
            }

            dashboardData.setCurrentCpu(cpu);
            dashboardData.setCurrentMem(mem);
            dashboardData.setCurrentLatency(lat);
            dashboardData.setCurrentRps(rps);

        } catch (Exception e) {
            // No simulation fallback - let dashboard show 0/Down
            dashboardData.setCurrentCpu(0.0);
            dashboardData.setCurrentMem(0.0);
            dashboardData.setCurrentLatency(0.0);
            dashboardData.setCurrentRps(0.0);
        }
    }

    private double fetchMetric(String query) {
        try {
            String url = String.format("%s/api/v1/query?query=%s", prometheusUrl, query);
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            if (response != null && "success".equals(response.get("status"))) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");
                if (!results.isEmpty()) {
                    List<Object> value = (List<Object>) results.get(0).get("value");
                    return Double.parseDouble(value.get(1).toString());
                }
            }
        } catch (Exception e) {
            // Silently fail and return 0 for simulation fallback
        }
        return 0.0;
    }
}
