package com.infobot.service;

import com.infobot.model.DriveDocument;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service for synchronizing documents from Google Drive and Confluence to Solr
 */
@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class DocumentSyncService {

    private final GoogleDriveService googleDriveService;
    private final ConfluenceService confluenceService;
    private final DocumentProcessorService documentProcessorService;
    private final SolrSearchService solrSearchService;

    @Value("${sync.enabled:true}")
    private boolean syncEnabled;

    private final AtomicBoolean syncInProgress = new AtomicBoolean(false);
    private final Set<String> indexedDocIds = new HashSet<>();

    @PostConstruct
    public void init() {
        if (syncEnabled) {
            log.info("Document sync service initialized.");

            // Load already indexed doc IDs from Solr to avoid re-indexing
            try {
                Set<String> existingDocIds = solrSearchService.getAllIndexedDocIds();
                indexedDocIds.addAll(existingDocIds);
                log.info("Loaded {} already-indexed documents from Solr", indexedDocIds.size());
            } catch (Exception e) {
                log.warn("Could not load existing doc IDs from Solr: {}", e.getMessage());
            }

            // Run initial sync in background
            log.info("Starting initial sync...");
            new Thread(this::syncDocuments).start();
        } else {
            log.info("Document sync is disabled");
        }
    }

    /**
     * Periodic sync - runs every 2 minutes
     */
    @Scheduled(fixedRateString = "${sync.interval-minutes:2}000" + "60")
    public void scheduledSync() {
        if (!syncEnabled) {
            return;
        }

        if (syncInProgress.get()) {
            log.info("Sync already in progress, skipping scheduled sync");
            return;
        }

        syncDocuments();
    }

    /**
     * Sync all documents from Google Drive and Confluence
     */
    public void syncDocuments() {
        if (!syncInProgress.compareAndSet(false, true)) {
            log.info("Sync already in progress");
            return;
        }

        try {
            log.info("Starting document synchronization...");

            int totalProcessed = 0;
            int totalSkipped = 0;
            int totalFailed = 0;

            // Sync from Google Drive
            if (googleDriveService.isAvailable()) {
                int[] driveStats = syncFromGoogleDrive();
                totalProcessed += driveStats[0];
                totalSkipped += driveStats[1];
                totalFailed += driveStats[2];
            } else {
                log.info("Google Drive service is not available - skipping");
            }

            // Sync from Confluence
            if (confluenceService.isAvailable()) {
                int[] confluenceStats = syncFromConfluence();
                totalProcessed += confluenceStats[0];
                totalSkipped += confluenceStats[1];
                totalFailed += confluenceStats[2];
            } else {
                log.info("Confluence service is not available - skipping");
            }

            log.info("Sync completed. Total - Processed: {}, Skipped: {}, Failed: {}",
                    totalProcessed, totalSkipped, totalFailed);
            log.info("Total documents indexed: {}", solrSearchService.getDocumentCount());

        } catch (Exception e) {
            log.error("Error during sync: {}", e.getMessage(), e);
        } finally {
            syncInProgress.set(false);
        }
    }

    /**
     * Sync documents from Google Drive
     */
    private int[] syncFromGoogleDrive() {
        int processed = 0;
        int skipped = 0;
        int failed = 0;

        List<DriveDocument> driveDocuments = googleDriveService.getAllDocuments();
        log.info("Found {} documents in Google Drive", driveDocuments.size());

        for (DriveDocument driveDoc : driveDocuments) {
            try {
                // Check if already indexed
                if (indexedDocIds.contains(driveDoc.getId())) {
                    skipped++;
                    continue;
                }

                // Download file content
                byte[] content = googleDriveService.downloadFile(driveDoc.getId(), driveDoc.getMimeType());

                if (content == null || content.length == 0) {
                    log.warn("Empty content for document: {}", driveDoc.getName());
                    failed++;
                    continue;
                }

                // Process and index
                boolean success = documentProcessorService.processDocument(driveDoc, content);

                if (success) {
                    indexedDocIds.add(driveDoc.getId());
                    processed++;
                    log.info("Processed (Drive): {} ({}/{})", driveDoc.getName(), processed, driveDocuments.size());
                } else {
                    failed++;
                }

            } catch (Exception e) {
                log.error("Error processing document {}: {}", driveDoc.getName(), e.getMessage());
                failed++;
            }
        }

        log.info("Google Drive sync: Processed: {}, Skipped: {}, Failed: {}", processed, skipped, failed);
        return new int[]{processed, skipped, failed};
    }

    /**
     * Sync documents from Confluence
     */
    private int[] syncFromConfluence() {
        int processed = 0;
        int skipped = 0;
        int failed = 0;

        List<DriveDocument> confluencePages = confluenceService.getAllDocuments();
        log.info("Found {} pages in Confluence", confluencePages.size());

        for (DriveDocument page : confluencePages) {
            try {
                // Check if already indexed
                if (indexedDocIds.contains(page.getId())) {
                    skipped++;
                    continue;
                }

                // Confluence pages already have content
                if (page.getContent() == null || page.getContent().isEmpty()) {
                    log.warn("Empty content for Confluence page: {}", page.getName());
                    failed++;
                    continue;
                }

                // Process and index directly (content is already text)
                boolean success = documentProcessorService.processConfluencePage(page);

                if (success) {
                    indexedDocIds.add(page.getId());
                    processed++;
                    log.info("Processed (Confluence): {} ({}/{})", page.getName(), processed, confluencePages.size());
                } else {
                    failed++;
                }

            } catch (Exception e) {
                log.error("Error processing Confluence page {}: {}", page.getName(), e.getMessage());
                failed++;
            }
        }

        log.info("Confluence sync: Processed: {}, Skipped: {}, Failed: {}", processed, skipped, failed);
        return new int[]{processed, skipped, failed};
    }

    /**
     * Force full re-sync
     */
    public void forceFullSync() {
        log.info("Starting full re-sync...");
        indexedDocIds.clear();
        solrSearchService.clearAll();
        syncDocuments();
    }

    /**
     * Get sync status
     */
    public String getStatus() {
        return String.format("Sync in progress: %s, Documents tracked: %d",
                syncInProgress.get(), indexedDocIds.size());
    }
}
