package com.infobot.service;

import com.infobot.model.Document;
import com.infobot.model.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Query Engine Service - orchestrates search and answer generation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryEngineService {

    private final SolrSearchService solrSearchService;
    private final GeminiService geminiService;

    @Value("${search.max-results:20}")
    private int maxSearchResults;

    // Patterns for detecting document name queries
    private static final Pattern DOC_NAME_PATTERN = Pattern.compile(
            "\\b[\\w\\-_]+\\.(doc|docx|pdf|txt|xlsx|pptx|csv)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern FILE_URL_PATTERN = Pattern.compile(
            "(?:file\\s*url|url|link)\\s+(?:of|for)?\\s*(.+)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Process user query and return response
     */
    public String processQuery(String query) {
        try {
            log.info("Processing query: {}", truncate(query, 100));

            // Check for special queries
            String specialResponse = handleSpecialQueries(query);
            if (specialResponse != null) {
                return specialResponse;
            }

            // Preprocess query
            String processedQuery = preprocessQuery(query);
            log.info("Processed query: {}", truncate(processedQuery, 150));

            // Search for relevant documents
            SearchResult searchResult;

            // Check if user is asking for a specific document
            if (isDocumentNameQuery(query)) {
                String docName = extractDocumentName(query);
                log.info("Detected document name query for: {}", docName);
                searchResult = solrSearchService.searchByDocName(docName, maxSearchResults);
            } else {
                searchResult = solrSearchService.search(processedQuery, maxSearchResults);
            }

            List<Document> documents = searchResult.getDocuments();
            log.info("Found {} documents", documents.size());

            // Generate answer using Gemini
            String answer;
            if (documents.isEmpty()) {
                answer = geminiService.generateGeneralAnswer(query);
            } else {
                // Use top documents for context
                List<Document> contextDocs = documents.subList(0, Math.min(10, documents.size()));
                answer = geminiService.generateAnswer(query, contextDocs);
            }

            log.info("Generated answer with {} documents", documents.size());
            return answer;

        } catch (Exception e) {
            log.error("Error processing query: {}", e.getMessage(), e);
            return "I encountered an error processing your question: " + e.getMessage();
        }
    }

    /**
     * Handle special queries (greetings, help, status)
     */
    private String handleSpecialQueries(String query) {
        String lowerQuery = query.toLowerCase().trim();

        // Greetings
        if (lowerQuery.matches("^(hi|hello|hey|good morning|good afternoon|good evening)\\b.*")) {
            return "Hello! I'm InfoBot, your document assistant. I can help you find information from your Google Drive documents. What would you like to know?";
        }

        // Help
        if (lowerQuery.equals("help") || lowerQuery.equals("?")) {
            return """
                    I'm InfoBot! I can help you with:
                    • Finding information in your documents
                    • Getting file URLs
                    • Answering questions about document content

                    Example queries:
                    • "Tell me about Practice Note 31A"
                    • "What is the leave policy?"
                    • "Give me the file url of [document name]"
                    """;
        }

        // Status
        if (lowerQuery.equals("status") || lowerQuery.contains("how many documents")) {
            long count = solrSearchService.getDocumentCount();
            boolean solrHealthy = solrSearchService.isHealthy();
            boolean geminiAvailable = geminiService.isAvailable();

            return String.format("""
                    📊 InfoBot Status:
                    • Solr: %s
                    • Gemini AI: %s
                    • Documents indexed: %d
                    """,
                    solrHealthy ? "✅ Healthy" : "❌ Unhealthy",
                    geminiAvailable ? "✅ Available" : "❌ Not configured",
                    count);
        }

        return null;
    }

    /**
     * Preprocess query for better search
     */
    private String preprocessQuery(String query) {
        String processed = query;

        // Extract document names and expand
        Matcher matcher = DOC_NAME_PATTERN.matcher(query);
        while (matcher.find()) {
            String docName = matcher.group();
            // Add expanded version (underscores to spaces)
            String expanded = docName.replaceAll("[_-]", " ");
            if (!expanded.equals(docName)) {
                processed += " " + expanded;
            }
        }

        // Handle "file url of X" queries
        Matcher urlMatcher = FILE_URL_PATTERN.matcher(query);
        if (urlMatcher.find()) {
            String target = urlMatcher.group(1).trim();
            processed = target + " " + target.replaceAll("[_-]", " ");
        }

        return processed;
    }

    /**
     * Check if query is asking for a specific document
     */
    private boolean isDocumentNameQuery(String query) {
        String lower = query.toLowerCase();

        // Check for document name patterns
        if (DOC_NAME_PATTERN.matcher(query).find()) {
            return true;
        }

        // Check for file URL requests
        if (lower.contains("file url") || lower.contains("link of") || lower.contains("url of")) {
            return true;
        }

        // Check for "tell me about [specific name]" patterns
        if (lower.contains("tell me about") || lower.contains("what is") || lower.contains("find")) {
            // Check if the query contains underscore-separated words (likely a document name)
            if (query.matches(".*\\b\\w+_\\w+.*")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Extract document name from query
     */
    private String extractDocumentName(String query) {
        // Try to extract filename with extension
        Matcher matcher = DOC_NAME_PATTERN.matcher(query);
        if (matcher.find()) {
            return matcher.group();
        }

        // Try to extract from "file url of X" pattern
        Matcher urlMatcher = FILE_URL_PATTERN.matcher(query);
        if (urlMatcher.find()) {
            return urlMatcher.group(1).trim();
        }

        // Try to extract underscore-separated name
        Pattern underscorePattern = Pattern.compile("\\b([A-Za-z0-9]+(?:_[A-Za-z0-9]+)+)\\b");
        Matcher underscoreMatcher = underscorePattern.matcher(query);
        if (underscoreMatcher.find()) {
            return underscoreMatcher.group(1);
        }

        // Fall back to extracting keywords after common phrases
        String lower = query.toLowerCase();
        String[] prefixes = {"tell me about", "what is", "find", "about", "url of", "file url of"};
        for (String prefix : prefixes) {
            int idx = lower.indexOf(prefix);
            if (idx >= 0) {
                String remainder = query.substring(idx + prefix.length()).trim();
                // Remove quotes and asterisks
                remainder = remainder.replaceAll("[\"*]", "").trim();
                if (!remainder.isEmpty()) {
                    return remainder;
                }
            }
        }

        return query;
    }

    /**
     * Truncate string for logging
     */
    private String truncate(String str, int maxLength) {
        if (str == null) return "";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }
}
