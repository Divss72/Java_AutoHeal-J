package com.autoheal.dashboard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class OpenAIService {

    @Value("${openai.api.key}")
    private String apiKey;

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";

    public String getInsight(String service, String action) {
        String prompt = "Explain why " + action + " was performed on " + service;
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("model", "gpt-3.5-turbo");
        
        Map<String, String> systemMsg = new java.util.HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", "You are a cloud DevOps assistant.");
        
        Map<String, String> userMsg = new java.util.HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);
        
        java.util.List<Map<String, String>> messages = new java.util.ArrayList<>();
        messages.add(systemMsg);
        messages.add(userMsg);
        
        body.put("messages", messages);
        body.put("max_tokens", 50);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        
        try {
            Map<String, Object> response = restTemplate.postForObject(API_URL, entity, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return (String) message.get("content");
        } catch (Exception e) {
            if (action.equals("restart_pod")) {
                return "Restarting the pod clears transient memory leaks and forces the application to re-initialize its active state.";
            } else if (action.equals("scale_deployment")) {
                return "Scaling the deployment horizontally redistributes traffic, lowering the average load across the instances.";
            }
            return "The executed action stabilizes the service by neutralizing the root cause of the anomaly.";
        }
    }
}
