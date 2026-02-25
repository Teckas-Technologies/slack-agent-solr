package com.infobot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.solr.client.solrj.beans.Field;

/**
 * Document model for Solr indexing
 * Represents a document chunk stored in Solr
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Document {

    @Field("id")
    private String id;

    @Field("doc_id")
    private String docId;

    @Field("doc_name")
    private String docName;

    @Field("doc_source")
    private String docSource;

    @Field("doc_type")
    private String docType;

    @Field("url")
    private String url;

    @Field("content")
    private String content;

    @Field("chunk_index")
    private Integer chunkIndex;

    @Field("modified_time")
    private String modifiedTime;

    @Field("created_time")
    private String createdTime;

    // Score from Solr search (not indexed)
    private Float score;
}
