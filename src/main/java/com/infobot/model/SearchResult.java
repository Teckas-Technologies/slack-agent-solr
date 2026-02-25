package com.infobot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Search result containing documents and metadata
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResult {

    private List<Document> documents;
    private long totalFound;
    private long queryTimeMs;
    private String query;
}
