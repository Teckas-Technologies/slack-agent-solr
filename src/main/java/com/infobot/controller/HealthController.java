package com.infobot.controller;

import com.infobot.service.GeminiService;
import com.infobot.service.GoogleDriveService;
import com.infobot.service.SolrSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
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

    /**
     * Get document stats by source
     */
    @GetMapping("/api/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new HashMap<>();

        long driveCount = solrSearchService.getDocumentCountBySource("google_drive");
        long confluenceCount = solrSearchService.getDocumentCountBySource("confluence");
        long totalChunks = solrSearchService.getDocumentCount();

        stats.put("google_drive_chunks", driveCount);
        stats.put("confluence_chunks", confluenceCount);
        stats.put("total_chunks", totalChunks);
        stats.put("google_drive_unique_docs", solrSearchService.getUniqueDocCountBySource("google_drive"));
        stats.put("confluence_unique_docs", solrSearchService.getUniqueDocCountBySource("confluence"));

        return ResponseEntity.ok(stats);
    }

    /**
     * List indexed documents from Google Drive
     */
    @GetMapping("/api/docs/drive")
    public ResponseEntity<Map<String, Object>> getDriveDocs() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> docs = solrSearchService.getIndexedDocsBySource("google_drive");
        result.put("source", "google_drive");
        result.put("count", docs.size());
        result.put("documents", docs);
        return ResponseEntity.ok(result);
    }

    /**
     * List indexed documents from Confluence
     */
    @GetMapping("/api/docs/confluence")
    public ResponseEntity<Map<String, Object>> getConfluenceDocs() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> docs = solrSearchService.getIndexedDocsBySource("confluence");
        result.put("source", "confluence");
        result.put("count", docs.size());
        result.put("documents", docs);
        return ResponseEntity.ok(result);
    }
}
