package com.infobot.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.infobot.model.Document;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Service for interacting with Google Gemini AI
 */
@Slf4j
@Service
public class GeminiService {

    @Value("${gemini.api-key:}")
    private String apiKey;

    @Value("${gemini.model:gemini-2.0-flash}")
    private String model;

    @Value("${gemini.temperature:0.3}")
    private double temperature;

    @Value("${gemini.max-output-tokens:2048}")
    private int maxOutputTokens;

    private OkHttpClient httpClient;
    private Gson gson;
    private String apiUrl;

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    @PostConstruct
    public void init() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();

        this.gson = new Gson();
        this.apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;

        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("Gemini API key not configured");
        } else {
            log.info("✅ Gemini AI service initialized with model: {}", model);
        }
    }

    /**
     * Generate answer using RAG with document context
     */
    public String generateAnswer(String query, List<Document> contextDocuments) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "Gemini AI is not configured. Please set GEMINI_API_KEY.";
        }

        try {
            // Build context from documents
            String context = buildContext(contextDocuments);

            // Create prompt
            String prompt = buildPrompt(query, context);

            // Call Gemini API
            String response = callGeminiApi(prompt);

            // Add source references
            String sources = buildSourceReferences(contextDocuments);

            return response + sources;

        } catch (Exception e) {
            log.error("Error generating answer: {}", e.getMessage(), e);
            return "I encountered an error processing your question: " + e.getMessage();
        }
    }

    /**
     * Generate answer for general queries (no context)
     */
    public String generateGeneralAnswer(String query) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "Gemini AI is not configured. Please set GEMINI_API_KEY.";
        }

        try {
            String prompt = "You are InfoBot, a helpful AI assistant. Answer the following question concisely:\n\n" +
                    "Question: " + query + "\n\nAnswer:";

            return callGeminiApi(prompt);

        } catch (Exception e) {
            log.error("Error generating general answer: {}", e.getMessage(), e);
            return "I encountered an error processing your question: " + e.getMessage();
        }
    }

    /**
     * Build context from documents
     */
    private String buildContext(List<Document> documents) {
        StringBuilder context = new StringBuilder();

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);

            context.append("---\n");
            context.append("Document ").append(i + 1).append(":\n");
            context.append("Title: ").append(doc.getDocName()).append("\n");
            context.append("Source: ").append(doc.getDocSource()).append("\n");
            context.append("Relevance Score: ").append(String.format("%.2f", doc.getScore())).append("\n");
            context.append("\nContent:\n");

            // Limit content to 2000 chars per doc
            String content = doc.getContent();
            if (content.length() > 2000) {
                content = content.substring(0, 2000) + "...";
            }
            context.append(content);
            context.append("\n---\n\n");
        }

        return context.toString();
    }

    /**
     * Build the RAG prompt
     */
    private String buildPrompt(String query, String context) {
        return """
                You are InfoBot, an intelligent AI assistant that answers questions based on document context.

                **Context Documents:**
                %s

                **User Question:** %s

                **Critical Instructions:**
                1. **First, carefully check if ANY of the provided context documents contain relevant information about the question.**
                2. **If the documents ARE relevant**: Answer using ONLY the information from these documents. Cite specific document sources.
                3. **If the documents are NOT relevant**: Clearly state "The provided context documents do not contain information about [topic]."
                4. **DO NOT make assumptions** - if the documents mention something vaguely related but don't actually answer the question, say so.
                5. **Be strict about relevance** - only use documents that directly address the user's question.
                6. If the user is asking for a file URL, provide it from the document metadata.

                **Answer:**
                """.formatted(context, query);
    }

    /**
     * Call Gemini API
     */
    private String callGeminiApi(String prompt) throws IOException {
        // Build request body
        JsonObject requestBody = new JsonObject();

        JsonArray contents = new JsonArray();
        JsonObject content = new JsonObject();

        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", prompt);
        parts.add(textPart);

        content.add("parts", parts);
        contents.add(content);
        requestBody.add("contents", contents);

        // Generation config
        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("temperature", temperature);
        generationConfig.addProperty("maxOutputTokens", maxOutputTokens);
        requestBody.add("generationConfig", generationConfig);

        // Safety settings
        JsonArray safetySettings = new JsonArray();
        String[] categories = {"HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT"};
        for (String category : categories) {
            JsonObject setting = new JsonObject();
            setting.addProperty("category", category);
            setting.addProperty("threshold", "BLOCK_NONE");
            safetySettings.add(setting);
        }
        requestBody.add("safetySettings", safetySettings);

        // Make request
        RequestBody body = RequestBody.create(gson.toJson(requestBody), JSON);
        Request request = new Request.Builder()
                .url(apiUrl)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                log.error("Gemini API error: {} - {}", response.code(), errorBody);
                throw new IOException("Gemini API error: " + response.code());
            }

            String responseBody = response.body().string();
            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

            // Extract text from response
            return jsonResponse
                    .getAsJsonArray("candidates")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString();
        }
    }

    /**
     * Build source references
     */
    private String buildSourceReferences(List<Document> documents) {
        if (documents.isEmpty()) {
            return "";
        }

        StringBuilder sources = new StringBuilder("\n\nHere are the references:\n");
        List<String> uniqueUrls = new ArrayList<>();

        for (Document doc : documents) {
            String url = doc.getUrl();
            if (url != null && !url.isEmpty() && !uniqueUrls.contains(url)) {
                uniqueUrls.add(url);
            }
        }

        for (int i = 0; i < Math.min(uniqueUrls.size(), 5); i++) {
            sources.append(i + 1).append(". ").append(uniqueUrls.get(i)).append("\n");
        }

        return sources.toString();
    }

    /**
     * Check if service is available
     */
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isEmpty();
    }
}
