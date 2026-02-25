package com.infobot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a document from Google Drive
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriveDocument {

    private String id;
    private String name;
    private String mimeType;
    private String webViewLink;
    private String modifiedTime;
    private String createdTime;
    private Long size;
    private String content;
}
