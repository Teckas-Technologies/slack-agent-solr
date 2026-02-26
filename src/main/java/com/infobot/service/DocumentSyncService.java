package com.infobot.service;

import com.infobot.model.DriveDocument;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
    private final Set<String> failedDocIds = new HashSet<>();  // Track permanently failed docs

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

            // Load failed doc IDs from Solr to avoid retrying permanently failed docs
            try {
                Set<String> existingFailedIds = solrSearchService.getFailedDocIds();
                failedDocIds.addAll(existingFailedIds);
                if (!failedDocIds.isEmpty()) {
                    log.info("Loaded {} previously failed documents from Solr (will skip)", failedDocIds.size());
                }
            } catch (Exception e) {
                log.warn("Could not load failed doc IDs from Solr: {}", e.getMessage());
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
        List<String> newlyFailedDocs = new ArrayList<>();

        List<DriveDocument> driveDocuments = googleDriveService.getAllDocuments();
        int totalDocs = driveDocuments.size();
        int alreadyDone = 0;

        // Count already processed/failed docs
        for (DriveDocument doc : driveDocuments) {
            if (indexedDocIds.contains(doc.getId()) || failedDocIds.contains(doc.getId())) {
                alreadyDone++;
            }
        }
        int remaining = totalDocs - alreadyDone;

        log.info("Found {} documents in Google Drive ({} indexed, {} failed, {} remaining to process)",
                totalDocs, indexedDocIds.size(), failedDocIds.size(), remaining);

        for (DriveDocument driveDoc : driveDocuments) {
            try {
                // Check if already indexed
                if (indexedDocIds.contains(driveDoc.getId())) {
                    skipped++;
                    continue;
                }

                // Check if previously failed (permanent failure)
                if (failedDocIds.contains(driveDoc.getId())) {
                    skipped++;
                    continue;
                }

                // Download file content
                byte[] content = googleDriveService.downloadFile(driveDoc.getId(), driveDoc.getMimeType());

                if (content == null || content.length == 0) {
                    log.warn("Empty content for document: {} - marking as failed", driveDoc.getName());
                    failedDocIds.add(driveDoc.getId());
                    newlyFailedDocs.add(driveDoc.getId());
                    failed++;
                    continue;
                }

                // Process and index
                boolean success = documentProcessorService.processDocument(driveDoc, content);

                if (success) {
                    indexedDocIds.add(driveDoc.getId());
                    processed++;
                    int progressPct = remaining > 0 ? (processed * 100 / remaining) : 100;
                    log.info("Processed (Drive): {} ({}/{} remaining, {}%)",
                            driveDoc.getName(), processed, remaining, progressPct);
                } else {
                    // Mark as permanently failed so we don't retry
                    failedDocIds.add(driveDoc.getId());
                    newlyFailedDocs.add(driveDoc.getId());
                    failed++;
                    log.debug("Marked {} as failed - will not retry", driveDoc.getName());
                }

            } catch (Exception e) {
                log.error("Error processing document {}: {}", driveDoc.getName(), e.getMessage());
                // Mark as failed to avoid infinite retries
                failedDocIds.add(driveDoc.getId());
                newlyFailedDocs.add(driveDoc.getId());
                failed++;
            }
        }

        // Persist newly failed doc IDs to Solr
        if (!newlyFailedDocs.isEmpty()) {
            try {
                solrSearchService.saveFailedDocIds(newlyFailedDocs);
                log.info("Saved {} newly failed document IDs to Solr", newlyFailedDocs.size());
            } catch (Exception e) {
                log.warn("Could not save failed doc IDs to Solr: {}", e.getMessage());
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
        List<String> newlyFailedDocs = new ArrayList<>();

        List<DriveDocument> confluencePages = confluenceService.getAllDocuments();
        int totalPages = confluencePages.size();
        int alreadyDone = 0;

        // Count already processed/failed pages
        for (DriveDocument page : confluencePages) {
            if (indexedDocIds.contains(page.getId()) || failedDocIds.contains(page.getId())) {
                alreadyDone++;
            }
        }
        int remaining = totalPages - alreadyDone;

        log.info("Found {} pages in Confluence ({} indexed, {} failed, {} remaining to process)",
                totalPages,
                (int) confluencePages.stream().filter(p -> indexedDocIds.contains(p.getId())).count(),
                (int) confluencePages.stream().filter(p -> failedDocIds.contains(p.getId())).count(),
                remaining);

        for (DriveDocument page : confluencePages) {
            try {
                // Check if already indexed
                if (indexedDocIds.contains(page.getId())) {
                    skipped++;
                    continue;
                }

                // Check if previously failed
                if (failedDocIds.contains(page.getId())) {
                    skipped++;
                    continue;
                }

                // Confluence pages already have content
                if (page.getContent() == null || page.getContent().isEmpty()) {
                    log.warn("Empty content for Confluence page: {} - marking as failed", page.getName());
                    failedDocIds.add(page.getId());
                    newlyFailedDocs.add(page.getId());
                    failed++;
                    continue;
                }

                // Process and index directly (content is already text)
                boolean success = documentProcessorService.processConfluencePage(page);

                if (success) {
                    indexedDocIds.add(page.getId());
                    processed++;
                    int progressPct = remaining > 0 ? (processed * 100 / remaining) : 100;
                    log.info("Processed (Confluence): {} ({}/{} remaining, {}%)",
                            page.getName(), processed, remaining, progressPct);
                } else {
                    failedDocIds.add(page.getId());
                    newlyFailedDocs.add(page.getId());
                    failed++;
                }

            } catch (Exception e) {
                log.error("Error processing Confluence page {}: {}", page.getName(), e.getMessage());
                failedDocIds.add(page.getId());
                newlyFailedDocs.add(page.getId());
                failed++;
            }
        }

        // Persist newly failed doc IDs to Solr
        if (!newlyFailedDocs.isEmpty()) {
            try {
                solrSearchService.saveFailedDocIds(newlyFailedDocs);
                log.info("Saved {} newly failed Confluence page IDs to Solr", newlyFailedDocs.size());
            } catch (Exception e) {
                log.warn("Could not save failed doc IDs to Solr: {}", e.getMessage());
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
        failedDocIds.clear();
        solrSearchService.clearAll();
        syncDocuments();
    }

    /**
     * Reset failed documents to retry them
     */
    public void resetFailedDocs() {
        log.info("Resetting {} failed documents for retry...", failedDocIds.size());
        try {
            solrSearchService.clearFailedDocIds();
        } catch (Exception e) {
            log.warn("Could not clear failed doc IDs from Solr: {}", e.getMessage());
        }
        failedDocIds.clear();
        log.info("Failed documents reset. They will be retried on next sync.");
    }

    /**
     * Get sync status
     */
    public String getStatus() {
        return String.format("Sync in progress: %s, Documents indexed: %d, Documents failed: %d",
                syncInProgress.get(), indexedDocIds.size(), failedDocIds.size());
    }

    /**
     * Get count of failed documents
     */
    public int getFailedCount() {
        return failedDocIds.size();
    }
}
