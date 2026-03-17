package com.infobot.controller;

import com.infobot.service.ConfluenceService;
import com.infobot.service.GeminiService;
import com.infobot.service.GoogleDriveService;
import com.infobot.service.SolrSearchService;
import com.infobot.model.DriveDocument;
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
    private final ConfluenceService confluenceService;

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

    /**
     * Test Confluence connection and fetch documents
     */
    @GetMapping("/api/test/confluence")
    public ResponseEntity<Map<String, Object>> testConfluence() {
        Map<String, Object> result = new HashMap<>();

        // Check if configured
        if (!confluenceService.isAvailable()) {
            result.put("status", "NOT_CONFIGURED");
            result.put("message", "Confluence credentials not configured. Check CONFLUENCE_BASE_URL, CONFLUENCE_USERNAME, and CONFLUENCE_API_TOKEN in .env");
            return ResponseEntity.ok(result);
        }

        result.put("configured", true);

        try {
            // Try to fetch documents
            List<DriveDocument> docs = confluenceService.getAllDocuments();

            if (docs.isEmpty()) {
                result.put("status", "NO_DOCUMENTS");
                result.put("message", "Connection successful but no documents found. Check if user has access to Confluence spaces.");
                result.put("documents_found", 0);
            } else {
                result.put("status", "SUCCESS");
                result.put("message", "Successfully connected to Confluence");
                result.put("documents_found", docs.size());

                // Show first 10 document names
                List<String> docNames = docs.stream()
                        .limit(10)
                        .map(DriveDocument::getName)
                        .toList();
                result.put("sample_documents", docNames);
            }

        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("message", "Failed to connect to Confluence: " + e.getMessage());
            result.put("error", e.getClass().getSimpleName());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Test Google Drive connection and fetch documents
     */
    @GetMapping("/api/test/drive")
    public ResponseEntity<Map<String, Object>> testDrive() {
        Map<String, Object> result = new HashMap<>();

        // Check if configured
        if (!googleDriveService.isAvailable()) {
            result.put("status", "NOT_CONFIGURED");
            result.put("message", "Google Drive not configured. Check GOOGLE_APPLICATION_CREDENTIALS and GOOGLE_DRIVE_FOLDER_IDS in .env");
            return ResponseEntity.ok(result);
        }

        result.put("configured", true);

        try {
            // Try to fetch documents
            List<DriveDocument> docs = googleDriveService.getAllDocuments();

            if (docs.isEmpty()) {
                result.put("status", "NO_DOCUMENTS");
                result.put("message", "Connection successful but no documents found. Check folder access permissions.");
                result.put("documents_found", 0);
            } else {
                result.put("status", "SUCCESS");
                result.put("message", "Successfully connected to Google Drive");
                result.put("documents_found", docs.size());

                // Show first 10 document names
                List<String> docNames = docs.stream()
                        .limit(10)
                        .map(DriveDocument::getName)
                        .toList();
                result.put("sample_documents", docNames);
            }

        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("message", "Failed to connect to Google Drive: " + e.getMessage());
            result.put("error", e.getClass().getSimpleName());
        }

        return ResponseEntity.ok(result);
    }
}
