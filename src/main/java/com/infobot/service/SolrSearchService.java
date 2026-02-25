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
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Service for Apache Solr search operations
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SolrSearchService {

    private final SolrClient solrClient;

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

            // Build Solr query with boosting
            SolrQuery solrQuery = new SolrQuery();

            // Use eDisMax query parser for advanced search
            solrQuery.setParam("defType", "edismax");

            // Query with boosting - doc_name gets higher boost
            String escapedQuery = escapeQueryChars(query);
            solrQuery.setQuery(escapedQuery);

            // Query fields with boost values
            solrQuery.setParam("qf",
                String.format("doc_name^%.1f content^%.1f", docNameBoost, contentBoost));

            // Phrase boost for exact matches
            solrQuery.setParam("pf", "doc_name^20 content^5");

            // Minimum should match
            solrQuery.setParam("mm", "50%");

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
