package com.infobot.controller;

import com.infobot.service.GeminiService;
import com.infobot.service.GoogleDriveService;
import com.infobot.service.SolrSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Health check endpoints
 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final SolrSearchService solrSearchService;
    private final GoogleDriveService googleDriveService;
    private final GeminiService geminiService;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new HashMap<>();

        status.put("status", "UP");
        status.put("solr", solrSearchService.isHealthy() ? "UP" : "DOWN");
        status.put("googleDrive", googleDriveService.isAvailable() ? "UP" : "DOWN");
        status.put("gemini", geminiService.isAvailable() ? "UP" : "DOWN");
        status.put("documentsIndexed", solrSearchService.getDocumentCount());

        return ResponseEntity.ok(status);
    }

    @GetMapping("/")
    public ResponseEntity<Map<String, String>> root() {
        return ResponseEntity.ok(Map.of(
                "application", "InfoBot - Slack Document Agent",
                "version", "1.0.0",
                "status", "running"
        ));
    }
}
