package com.infobot.service;

import com.infobot.model.Document;
import com.infobot.model.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for Apache Solr search operations
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SolrSearchService {

    private final SolrClient solrClient;

    // Common English stop words to filter from queries
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
        "a", "an", "the", "and", "or", "but", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "could", "should", "may", "might",
        "must", "shall", "can", "need", "dare", "ought", "used", "to", "of", "in", "for", "on", "with",
        "at", "by", "from", "as", "into", "through", "during", "before", "after", "above", "below",
        "between", "under", "again", "further", "then", "once", "here", "there", "when", "where",
        "why", "how", "all", "each", "few", "more", "most", "other", "some", "such", "no", "nor",
        "not", "only", "own", "same", "so", "than", "too", "very", "just", "also", "now", "i", "me",
        "my", "myself", "we", "our", "ours", "ourselves", "you", "your", "yours", "yourself",
        "he", "him", "his", "himself", "she", "her", "hers", "herself", "it", "its", "itself",
        "they", "them", "their", "theirs", "themselves", "what", "which", "who", "whom", "this",
        "that", "these", "those", "am", "about", "against", "any", "because", "both", "but", "if",
        "while", "until", "up", "down", "out", "off", "over", "under", "get", "give", "go", "find",
        "file", "document", "documents", "files", "want", "need", "please", "help", "show", "tell"
    ));

    @Value("${search.max-results:20}")
    private int maxResults;

    @Value("${search.min-score:0.1}")
    private float minScore;

    @Value("${search.boost.doc-name:10.0}")
    private float docNameBoost;

    @Value("${search.boost.content:1.0}")
    private float contentBoost;

    /**
     * Add documents to Solr index
     */
    public boolean addDocuments(List<Document> documents) {
        // Check for empty list to avoid "missing content stream" error
        if (documents == null || documents.isEmpty()) {
            log.warn("No documents to add to Solr (empty list)");
            return false;
        }

        try {
            Collection<SolrInputDocument> solrDocs = new ArrayList<>();

            for (Document doc : documents) {
                SolrInputDocument solrDoc = new SolrInputDocument();
                solrDoc.addField("id", doc.getId());
                solrDoc.addField("doc_id", doc.getDocId());
                solrDoc.addField("doc_name", doc.getDocName());
                solrDoc.addField("doc_source", doc.getDocSource());
                solrDoc.addField("doc_type", doc.getDocType());
                solrDoc.addField("url", doc.getUrl());
                solrDoc.addField("content", doc.getContent());
                solrDoc.addField("chunk_index", doc.getChunkIndex());
                solrDoc.addField("modified_time", doc.getModifiedTime());
                solrDoc.addField("created_time", doc.getCreatedTime());
                solrDocs.add(solrDoc);
            }

            UpdateResponse response = solrClient.add(solrDocs);
            solrClient.commit();

            log.info("Added {} documents to Solr. Status: {}", documents.size(), response.getStatus());
            return response.getStatus() == 0;

        } catch (SolrServerException | IOException e) {
            log.error("Error adding documents to Solr: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Search documents using Solr
     */
    public SearchResult search(String query, int numResults) {
        try {
            long startTime = System.currentTimeMillis();

            // Remove stop words from query for better relevance
            String filteredQuery = removeStopWords(query);
            log.info("Original query: '{}' -> Filtered query: '{}'", query, filteredQuery);

            // If all words were stop words, use original query
            if (filteredQuery.trim().isEmpty()) {
                filteredQuery = query;
            }

            // Build Solr query with boosting
            SolrQuery solrQuery = new SolrQuery();

            // Use eDisMax query parser for advanced search
            solrQuery.setParam("defType", "edismax");

            // Query with boosting - doc_name gets higher boost
            String escapedQuery = escapeQueryChars(filteredQuery);
            solrQuery.setQuery(escapedQuery);

            // Query fields with boost values - significantly higher boost for doc_name
            solrQuery.setParam("qf",
                String.format("doc_name^%.1f content^%.1f", docNameBoost * 5, contentBoost));

            // Phrase boost for exact matches - high boost for exact phrase in content
            solrQuery.setParam("pf", "doc_name^100 content^50");
            solrQuery.setParam("pf2", "doc_name^50 content^25");
            solrQuery.setParam("pf3", "doc_name^25 content^10");

            // Phrase slop - allow words within N positions for phrase matching
            solrQuery.setParam("ps", "2");
            solrQuery.setParam("ps2", "4");
            solrQuery.setParam("ps3", "6");

            // Tie breaker to allow other fields to contribute
            solrQuery.setParam("tie", "0.1");

            // Minimum should match - more lenient for better recall
            solrQuery.setParam("mm", "2<50% 4<40%");

            // Number of results
            solrQuery.setRows(numResults > 0 ? numResults : maxResults);

            // Return all fields plus score
            solrQuery.setFields("*", "score");

            // Enable highlighting
            solrQuery.setHighlight(true);
            solrQuery.addHighlightField("content");
            solrQuery.setHighlightSimplePre("<em>");
            solrQuery.setHighlightSimplePost("</em>");
            solrQuery.setHighlightSnippets(3);

            // Execute search
            QueryResponse response = solrClient.query(solrQuery);
            SolrDocumentList results = response.getResults();

            // Convert to Document objects
            List<Document> documents = new ArrayList<>();
            for (SolrDocument solrDoc : results) {
                Document doc = Document.builder()
                        .id(getStringField(solrDoc, "id"))
                        .docId(getStringField(solrDoc, "doc_id"))
                        .docName(getStringField(solrDoc, "doc_name"))
                        .docSource(getStringField(solrDoc, "doc_source"))
                        .docType(getStringField(solrDoc, "doc_type"))
                        .url(getStringField(solrDoc, "url"))
                        .content(getStringField(solrDoc, "content"))
                        .chunkIndex(getIntField(solrDoc, "chunk_index"))
                        .modifiedTime(getStringField(solrDoc, "modified_time"))
                        .createdTime(getStringField(solrDoc, "created_time"))
                        .score((Float) solrDoc.getFieldValue("score"))
                        .build();

                // Apply minimum score filter
                if (doc.getScore() != null && doc.getScore() >= minScore) {
                    documents.add(doc);
                }
            }

            long queryTime = System.currentTimeMillis() - startTime;

            log.info("Solr search for '{}' found {} documents in {}ms",
                    query, documents.size(), queryTime);

            // Log top results for debugging
            for (int i = 0; i < Math.min(3, documents.size()); i++) {
                Document d = documents.get(i);
                log.info("  [{}] {} (score: {})", i + 1, d.getDocName(), d.getScore());
            }

            return SearchResult.builder()
                    .documents(documents)
                    .totalFound(results.getNumFound())
                    .queryTimeMs(queryTime)
                    .query(query)
                    .build();

        } catch (SolrServerException | IOException e) {
            log.error("Error searching Solr: {}", e.getMessage(), e);
            return SearchResult.builder()
                    .documents(new ArrayList<>())
                    .totalFound(0)
                    .queryTimeMs(0)
                    .query(query)
                    .build();
        }
    }

    /**
     * Search by document name (exact or partial match)
     */
    public SearchResult searchByDocName(String docName, int numResults) {
        try {
            SolrQuery solrQuery = new SolrQuery();
            solrQuery.setParam("defType", "edismax");

            String escapedName = escapeQueryChars(docName);
            solrQuery.setQuery(escapedName);

            // Heavy boost on doc_name for filename searches
            solrQuery.setParam("qf", "doc_name^50 content^1");
            solrQuery.setParam("pf", "doc_name^100");
            solrQuery.setParam("mm", "75%");
            solrQuery.setRows(numResults > 0 ? numResults : maxResults);
            solrQuery.setFields("*", "score");

            QueryResponse response = solrClient.query(solrQuery);
            SolrDocumentList results = response.getResults();

            List<Document> documents = new ArrayList<>();
            for (SolrDocument solrDoc : results) {
                Document doc = Document.builder()
                        .id(getStringField(solrDoc, "id"))
                        .docId(getStringField(solrDoc, "doc_id"))
                        .docName(getStringField(solrDoc, "doc_name"))
                        .docSource(getStringField(solrDoc, "doc_source"))
                        .docType(getStringField(solrDoc, "doc_type"))
                        .url(getStringField(solrDoc, "url"))
                        .content(getStringField(solrDoc, "content"))
                        .chunkIndex(getIntField(solrDoc, "chunk_index"))
                        .modifiedTime(getStringField(solrDoc, "modified_time"))
                        .score((Float) solrDoc.getFieldValue("score"))
                        .build();
                documents.add(doc);
            }

            log.info("Doc name search for '{}' found {} documents", docName, documents.size());

            return SearchResult.builder()
                    .documents(documents)
                    .totalFound(results.getNumFound())
                    .query(docName)
                    .build();

        } catch (SolrServerException | IOException e) {
            log.error("Error searching by doc name: {}", e.getMessage(), e);
            return SearchResult.builder()
                    .documents(new ArrayList<>())
                    .totalFound(0)
                    .query(docName)
                    .build();
        }
    }

    /**
     * Delete document by ID
     */
    public boolean deleteById(String id) {
        try {
            solrClient.deleteById(id);
            solrClient.commit();
            log.info("Deleted document with ID: {}", id);
            return true;
        } catch (SolrServerException | IOException e) {
            log.error("Error deleting document: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Delete all chunks for a document by doc_id
     */
    public boolean deleteByDocId(String docId) {
        try {
            solrClient.deleteByQuery("doc_id:" + escapeQueryChars(docId));
            solrClient.commit();
            log.info("Deleted all chunks for doc_id: {}", docId);
            return true;
        } catch (SolrServerException | IOException e) {
            log.error("Error deleting by doc_id: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Clear all documents from index
     */
    public boolean clearAll() {
        try {
            solrClient.deleteByQuery("*:*");
            solrClient.commit();
            log.info("Cleared all documents from Solr");
            return true;
        } catch (SolrServerException | IOException e) {
            log.error("Error clearing Solr: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Get total document count
     */
    public long getDocumentCount() {
        try {
            SolrQuery query = new SolrQuery("*:*");
            query.setRows(0);
            QueryResponse response = solrClient.query(query);
            return response.getResults().getNumFound();
        } catch (SolrServerException | IOException e) {
            log.error("Error getting document count: {}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Get all unique doc_ids that are already indexed in Solr
     * Used at startup to avoid re-indexing existing documents
     */
    public Set<String> getAllIndexedDocIds() {
        Set<String> docIds = new HashSet<>();
        try {
            SolrQuery query = new SolrQuery("*:*");
            query.setFields("doc_id");
            query.setRows(0);

            // Use faceting to get unique doc_ids efficiently
            query.setFacet(true);
            query.addFacetField("doc_id");
            query.setFacetLimit(-1); // No limit - get all unique values
            query.setFacetMinCount(1);

            QueryResponse response = solrClient.query(query);

            if (response.getFacetField("doc_id") != null) {
                response.getFacetField("doc_id").getValues().forEach(count -> {
                    if (count.getName() != null) {
                        docIds.add(count.getName());
                    }
                });
            }

            log.info("Loaded {} unique doc_ids from Solr index", docIds.size());

        } catch (SolrServerException | IOException e) {
            log.error("Error getting indexed doc_ids: {}", e.getMessage(), e);
        }
        return docIds;
    }

    /**
     * Get all failed doc_ids from Solr
     * Failed docs are stored with doc_source = "_system_failed_"
     */
    public Set<String> getFailedDocIds() {
        Set<String> failedIds = new HashSet<>();
        try {
            SolrQuery query = new SolrQuery("doc_source:_system_failed_");
            query.setFields("doc_id");
            query.setRows(Integer.MAX_VALUE);

            QueryResponse response = solrClient.query(query);
            for (SolrDocument doc : response.getResults()) {
                String docId = getStringField(doc, "doc_id");
                if (!docId.isEmpty()) {
                    failedIds.add(docId);
                }
            }

            if (!failedIds.isEmpty()) {
                log.info("Loaded {} failed doc_ids from Solr", failedIds.size());
            }

        } catch (SolrServerException | IOException e) {
            log.error("Error getting failed doc_ids: {}", e.getMessage(), e);
        }
        return failedIds;
    }

    /**
     * Save failed doc_ids to Solr
     * Each failed doc is stored as a marker document with doc_source = "_system_failed_"
     */
    public void saveFailedDocIds(List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return;
        }

        try {
            Collection<SolrInputDocument> solrDocs = new ArrayList<>();

            for (String docId : docIds) {
                SolrInputDocument solrDoc = new SolrInputDocument();
                solrDoc.addField("id", "_failed_" + docId);
                solrDoc.addField("doc_id", docId);
                solrDoc.addField("doc_source", "_system_failed_");
                solrDoc.addField("doc_name", "Failed Document Marker");
                solrDoc.addField("content", "This document failed to process");
                solrDocs.add(solrDoc);
            }

            solrClient.add(solrDocs);
            solrClient.commit();

            log.debug("Saved {} failed doc_ids to Solr", docIds.size());

        } catch (SolrServerException | IOException e) {
            log.error("Error saving failed doc_ids: {}", e.getMessage(), e);
        }
    }

    /**
     * Clear all failed doc_ids from Solr
     */
    public void clearFailedDocIds() {
        try {
            solrClient.deleteByQuery("doc_source:_system_failed_");
            solrClient.commit();
            log.info("Cleared all failed doc_ids from Solr");
        } catch (SolrServerException | IOException e) {
            log.error("Error clearing failed doc_ids: {}", e.getMessage(), e);
        }
    }

    /**
     * Check if Solr is healthy
     */
    public boolean isHealthy() {
        try {
            SolrQuery query = new SolrQuery("*:*");
            query.setRows(0);
            solrClient.query(query);
            return true;
        } catch (Exception e) {
            log.error("Solr health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Remove stop words from query for better search relevance
     */
    private String removeStopWords(String query) {
        if (query == null || query.isEmpty()) {
            return query;
        }
        return Arrays.stream(query.toLowerCase().split("\\s+"))
                .filter(word -> !STOP_WORDS.contains(word.toLowerCase()))
                .collect(Collectors.joining(" "));
    }

    /**
     * Escape special characters in Solr query
     */
    private String escapeQueryChars(String query) {
        StringBuilder sb = new StringBuilder();
        for (char c : query.toCharArray()) {
            // Escape special characters but keep spaces
            if (c == '\\' || c == '+' || c == '-' || c == '!' || c == '(' || c == ')'
                || c == ':' || c == '^' || c == '[' || c == ']' || c == '\"'
                || c == '{' || c == '}' || c == '~' || c == '?' || c == '|'
                || c == '&' || c == ';' || c == '/') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private String getStringField(SolrDocument doc, String field) {
        Object value = doc.getFieldValue(field);
        if (value == null) return "";
        if (value instanceof java.util.Collection) {
            java.util.Collection<?> col = (java.util.Collection<?>) value;
            return col.isEmpty() ? "" : col.iterator().next().toString();
        }
        return value.toString();
    }

    private Integer getIntField(SolrDocument doc, String field) {
        Object value = doc.getFieldValue(field);
        if (value == null) return 0;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof java.util.Collection) {
            java.util.Collection<?> col = (java.util.Collection<?>) value;
            if (col.isEmpty()) return 0;
            Object first = col.iterator().next();
            if (first instanceof Integer) return (Integer) first;
            return Integer.parseInt(first.toString());
        }
        return Integer.parseInt(value.toString());
    }
}
