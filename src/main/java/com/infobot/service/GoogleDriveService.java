package com.infobot.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.infobot.model.DriveDocument;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.*;

/**
 * Service for interacting with Google Drive
 */
@Slf4j
@Service
public class GoogleDriveService {

    @Value("${google.drive.credentials-file:}")
    private String credentialsFile;

    @Value("${google.drive.delegated-user:}")
    private String delegatedUser;

    @Value("${google.drive.folder-ids:}")
    private String folderIds;

    private Drive driveService;

    private static final String FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";
    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/plain",
            "text/csv",
            "application/vnd.google-apps.document",
            "application/vnd.google-apps.spreadsheet"
    );

    @PostConstruct
    public void init() {
        try {
            // Try to load credentials from file or environment
            GoogleCredentials credentials = loadCredentials();

            if (credentials == null) {
                log.warn("Google Drive credentials not configured");
                return;
            }

            credentials = credentials.createScoped(Collections.singletonList(DriveScopes.DRIVE_READONLY));

            // Apply domain-wide delegation if delegated user is specified
            if (delegatedUser != null && !delegatedUser.isEmpty()) {
                credentials = ((ServiceAccountCredentials) credentials).createDelegated(delegatedUser);
                log.info("Using domain-wide delegation with user: {}", delegatedUser);
            }

            driveService = new Drive.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("InfoBot")
                    .build();

            log.info("✅ Google Drive service initialized successfully");

        } catch (IOException | GeneralSecurityException e) {
            log.error("Failed to initialize Google Drive service: {}", e.getMessage(), e);
        }
    }

    /**
     * Get all documents from configured folders
     */
    public List<DriveDocument> getAllDocuments() {
        List<DriveDocument> allDocuments = new ArrayList<>();

        if (driveService == null) {
            log.error("Google Drive service not initialized");
            return allDocuments;
        }

        try {
            // Get configured folder IDs
            List<String> configuredFolderIds = getConfiguredFolderIds();

            if (configuredFolderIds.isEmpty()) {
                log.info("No folder IDs configured, fetching all accessible documents");
                return getAllAccessibleDocuments();
            }

            // Get all folders (including subfolders)
            Set<String> allFolderIds = new HashSet<>();
            for (String folderId : configuredFolderIds) {
                allFolderIds.add(folderId);
                allFolderIds.addAll(getSubfolders(folderId));
            }

            log.info("Found {} total folders to scan", allFolderIds.size());

            // Get documents from each folder
            for (String folderId : allFolderIds) {
                List<DriveDocument> folderDocs = getDocumentsFromFolder(folderId);
                allDocuments.addAll(folderDocs);
            }

            log.info("Found {} total documents from Google Drive", allDocuments.size());

        } catch (Exception e) {
            log.error("Error fetching documents from Google Drive: {}", e.getMessage(), e);
        }

        return allDocuments;
    }

    /**
     * Get all subfolders recursively
     */
    private Set<String> getSubfolders(String parentFolderId) {
        Set<String> subfolders = new HashSet<>();

        try {
            String query = String.format("'%s' in parents and mimeType='%s' and trashed=false",
                    parentFolderId, FOLDER_MIME_TYPE);

            FileList result = driveService.files().list()
                    .setQ(query)
                    .setFields("files(id, name)")
                    .setPageSize(1000)
                    .execute();

            for (File folder : result.getFiles()) {
                subfolders.add(folder.getId());
                // Recursively get subfolders
                subfolders.addAll(getSubfolders(folder.getId()));
            }

        } catch (IOException e) {
            log.error("Error getting subfolders for {}: {}", parentFolderId, e.getMessage());
        }

        return subfolders;
    }

    /**
     * Get documents from a specific folder
     */
    private List<DriveDocument> getDocumentsFromFolder(String folderId) {
        List<DriveDocument> documents = new ArrayList<>();

        try {
            String query = String.format("'%s' in parents and mimeType!='%s' and trashed=false",
                    folderId, FOLDER_MIME_TYPE);

            FileList result = driveService.files().list()
                    .setQ(query)
                    .setFields("files(id, name, mimeType, webViewLink, modifiedTime, createdTime, size)")
                    .setPageSize(1000)
                    .execute();

            for (File file : result.getFiles()) {
                if (isSupportedFile(file)) {
                    DriveDocument doc = DriveDocument.builder()
                            .id(file.getId())
                            .name(file.getName())
                            .mimeType(file.getMimeType())
                            .webViewLink(file.getWebViewLink())
                            .modifiedTime(file.getModifiedTime() != null ? file.getModifiedTime().toStringRfc3339() : null)
                            .createdTime(file.getCreatedTime() != null ? file.getCreatedTime().toStringRfc3339() : null)
                            .size(file.getSize())
                            .build();
                    documents.add(doc);
                }
            }

        } catch (IOException e) {
            log.error("Error getting documents from folder {}: {}", folderId, e.getMessage());
        }

        return documents;
    }

    /**
     * Get all accessible documents (when no folder is configured)
     */
    private List<DriveDocument> getAllAccessibleDocuments() {
        List<DriveDocument> documents = new ArrayList<>();

        try {
            String pageToken = null;

            do {
                FileList result = driveService.files().list()
                        .setQ("mimeType!='" + FOLDER_MIME_TYPE + "' and trashed=false")
                        .setFields("nextPageToken, files(id, name, mimeType, webViewLink, modifiedTime, createdTime, size)")
                        .setPageSize(100)
                        .setPageToken(pageToken)
                        .execute();

                for (File file : result.getFiles()) {
                    if (isSupportedFile(file)) {
                        DriveDocument doc = DriveDocument.builder()
                                .id(file.getId())
                                .name(file.getName())
                                .mimeType(file.getMimeType())
                                .webViewLink(file.getWebViewLink())
                                .modifiedTime(file.getModifiedTime() != null ? file.getModifiedTime().toStringRfc3339() : null)
                                .createdTime(file.getCreatedTime() != null ? file.getCreatedTime().toStringRfc3339() : null)
                                .size(file.getSize())
                                .build();
                        documents.add(doc);
                    }
                }

                pageToken = result.getNextPageToken();
            } while (pageToken != null);

        } catch (IOException e) {
            log.error("Error fetching all accessible documents: {}", e.getMessage());
        }

        return documents;
    }

    /**
     * Download file content
     */
    public byte[] downloadFile(String fileId, String mimeType) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

            // Handle Google Docs native formats
            if (mimeType != null && mimeType.startsWith("application/vnd.google-apps")) {
                String exportMimeType = getExportMimeType(mimeType);
                driveService.files().export(fileId, exportMimeType)
                        .executeMediaAndDownloadTo(outputStream);
            } else {
                driveService.files().get(fileId)
                        .executeMediaAndDownloadTo(outputStream);
            }

            return outputStream.toByteArray();

        } catch (IOException e) {
            log.error("Error downloading file {}: {}", fileId, e.getMessage());
            return null;
        }
    }

    /**
     * Get export mime type for Google Docs formats
     */
    private String getExportMimeType(String googleMimeType) {
        return switch (googleMimeType) {
            case "application/vnd.google-apps.document" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "application/vnd.google-apps.spreadsheet" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "application/vnd.google-apps.presentation" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default -> "application/pdf";
        };
    }

    /**
     * Check if file is supported
     */
    private boolean isSupportedFile(File file) {
        String mimeType = file.getMimeType();
        String name = file.getName().toLowerCase();

        // Check by mime type
        if (SUPPORTED_MIME_TYPES.contains(mimeType)) {
            return true;
        }

        // Check by extension
        return name.endsWith(".pdf") || name.endsWith(".doc") || name.endsWith(".docx")
                || name.endsWith(".xls") || name.endsWith(".xlsx")
                || name.endsWith(".txt") || name.endsWith(".csv");
    }

    /**
     * Get configured folder IDs
     */
    private List<String> getConfiguredFolderIds() {
        if (folderIds == null || folderIds.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.asList(folderIds.split(","));
    }

    /**
     * Check if service is available
     */
    public boolean isAvailable() {
        return driveService != null;
    }

    /**
     * Load Google credentials from file or environment
     */
    private GoogleCredentials loadCredentials() {
        try {
            // Option 1: Load from file path specified in config
            if (credentialsFile != null && !credentialsFile.isEmpty()) {
                java.io.File file = new java.io.File(credentialsFile);
                if (file.exists()) {
                    log.info("Loading credentials from file: {}", credentialsFile);
                    return ServiceAccountCredentials.fromStream(new java.io.FileInputStream(file));
                }
            }

            // Option 2: Load from GOOGLE_APPLICATION_CREDENTIALS environment variable
            String envCredentials = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
            if (envCredentials != null && !envCredentials.isEmpty()) {
                java.io.File file = new java.io.File(envCredentials);
                if (file.exists()) {
                    log.info("Loading credentials from GOOGLE_APPLICATION_CREDENTIALS: {}", envCredentials);
                    return ServiceAccountCredentials.fromStream(new java.io.FileInputStream(file));
                }
            }

            // Option 3: Try Application Default Credentials
            try {
                log.info("Trying Application Default Credentials");
                return GoogleCredentials.getApplicationDefault();
            } catch (Exception e) {
                log.debug("Application Default Credentials not available");
            }

            return null;

        } catch (Exception e) {
            log.error("Error loading Google credentials: {}", e.getMessage());
            return null;
        }
    }
}
