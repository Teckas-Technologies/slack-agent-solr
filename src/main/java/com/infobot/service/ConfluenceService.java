package com.infobot.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.infobot.model.DriveDocument;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Service for fetching documents from Atlassian Confluence
 */
@Slf4j
@Service
public class ConfluenceService {

    @Value("${confluence.base-url:}")
    private String baseUrl;

    @Value("${confluence.username:}")
    private String username;

    @Value("${confluence.api-token:}")
    private String apiToken;

    @Value("${confluence.spaces:}")
    private String configuredSpaces;

    private OkHttpClient httpClient;
    private Gson gson;
    private String authHeader;

    private static final MediaType JSON = MediaType.parse("application/json");

    @PostConstruct
    public void init() {
        if (baseUrl == null || baseUrl.isEmpty()) {
            log.info("Confluence not configured - skipping initialization");
            return;
        }

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();

        this.gson = new Gson();

        // Create Basic Auth header
        String credentials = username + ":" + apiToken;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        log.info("✅ Confluence service initialized for: {}", baseUrl);
    }

    /**
     * Check if Confluence is configured
     */
    public boolean isAvailable() {
        return baseUrl != null && !baseUrl.isEmpty()
                && username != null && !username.isEmpty()
                && apiToken != null && !apiToken.isEmpty();
    }

    /**
     * Get all documents from Confluence
     */
    public List<DriveDocument> getAllDocuments() {
        List<DriveDocument> documents = new ArrayList<>();

        if (!isAvailable()) {
            return documents;
        }

        try {
            // Get spaces to fetch
            List<String> spaces = getSpacesToFetch();
            log.info("Fetching pages from {} Confluence spaces", spaces.size());

            for (String spaceKey : spaces) {
                List<DriveDocument> spaceDocs = getPagesFromSpace(spaceKey);
                documents.addAll(spaceDocs);
            }

            log.info("Found {} total pages from Confluence", documents.size());

        } catch (Exception e) {
            log.error("Error fetching Confluence documents: {}", e.getMessage(), e);
        }

        return documents;
    }

    /**
     * Get list of spaces to fetch
     */
    private List<String> getSpacesToFetch() {
        List<String> spaces = new ArrayList<>();

        // If specific spaces configured, use those
        if (configuredSpaces != null && !configuredSpaces.trim().isEmpty()) {
            for (String space : configuredSpaces.split(",")) {
                spaces.add(space.trim());
            }
            return spaces;
        }

        // Otherwise, fetch all accessible spaces
        try {
            String url = baseUrl + "/wiki/rest/api/space?limit=100";
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", authHeader)
                    .header("Accept", "application/json")
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
                    JsonArray results = json.getAsJsonArray("results");

                    for (JsonElement element : results) {
                        JsonObject space = element.getAsJsonObject();
                        String key = space.get("key").getAsString();
                        spaces.add(key);
                    }
                }
            }

            log.info("Found {} Confluence spaces", spaces.size());

        } catch (Exception e) {
            log.error("Error fetching Confluence spaces: {}", e.getMessage());
        }

        return spaces;
    }

    /**
     * Get all pages from a space
     */
    private List<DriveDocument> getPagesFromSpace(String spaceKey) {
        List<DriveDocument> pages = new ArrayList<>();

        try {
            String url = baseUrl + "/wiki/rest/api/content?spaceKey=" + spaceKey
                    + "&type=page&expand=body.storage,version,history&limit=100";

            String nextUrl = url;

            while (nextUrl != null) {
                Request request = new Request.Builder()
                        .url(nextUrl)
                        .header("Authorization", authHeader)
                        .header("Accept", "application/json")
                        .get()
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        log.error("Error fetching pages from space {}: {}", spaceKey, response.code());
                        break;
                    }

                    JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
                    JsonArray results = json.getAsJsonArray("results");

                    for (JsonElement element : results) {
                        JsonObject page = element.getAsJsonObject();
                        DriveDocument doc = parseConfluencePage(page, spaceKey);
                        if (doc != null) {
                            pages.add(doc);
                        }
                    }

                    // Check for more pages
                    if (json.has("_links")) {
                        JsonObject links = json.getAsJsonObject("_links");
                        if (links.has("next")) {
                            nextUrl = baseUrl + links.get("next").getAsString();
                        } else {
                            nextUrl = null;
                        }
                    } else {
                        nextUrl = null;
                    }
                }
            }

            log.info("Found {} pages in space {}", pages.size(), spaceKey);

        } catch (Exception e) {
            log.error("Error fetching pages from space {}: {}", spaceKey, e.getMessage());
        }

        return pages;
    }

    /**
     * Parse Confluence page JSON to DriveDocument
     */
    private DriveDocument parseConfluencePage(JsonObject page, String spaceKey) {
        try {
            String id = page.get("id").getAsString();
            String title = page.get("title").getAsString();

            // Get content
            String htmlContent = "";
            if (page.has("body") && page.getAsJsonObject("body").has("storage")) {
                htmlContent = page.getAsJsonObject("body")
                        .getAsJsonObject("storage")
                        .get("value").getAsString();
            }

            // Convert HTML to plain text
            String textContent = Jsoup.parse(htmlContent).text();

            // Get URL
            String webUrl = baseUrl + "/wiki" + page.getAsJsonObject("_links").get("webui").getAsString();

            // Get modified time
            String modifiedTime = null;
            if (page.has("version")) {
                JsonObject version = page.getAsJsonObject("version");
                if (version.has("when")) {
                    modifiedTime = version.get("when").getAsString();
                }
            }

            return DriveDocument.builder()
                    .id("confluence_" + id)
                    .name(title)
                    .mimeType("confluence/page")
                    .webViewLink(webUrl)
                    .modifiedTime(modifiedTime)
                    .content(textContent)
                    .build();

        } catch (Exception e) {
            log.error("Error parsing Confluence page: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get content of a specific page
     */
    public String getPageContent(String pageId) {
        try {
            String url = baseUrl + "/wiki/rest/api/content/" + pageId + "?expand=body.storage";

            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", authHeader)
                    .header("Accept", "application/json")
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);

                    if (json.has("body") && json.getAsJsonObject("body").has("storage")) {
                        String htmlContent = json.getAsJsonObject("body")
                                .getAsJsonObject("storage")
                                .get("value").getAsString();

                        return Jsoup.parse(htmlContent).text();
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error fetching page content: {}", e.getMessage());
        }

        return null;
    }
}
