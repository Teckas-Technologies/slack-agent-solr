package com.infobot.service;

import com.infobot.model.Document;
import com.infobot.model.DriveDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Service for processing documents and creating chunks
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProcessorService {

    private final SolrSearchService solrSearchService;

    @Value("${document.chunk-size:1000}")
    private int chunkSize;

    @Value("${document.chunk-overlap:200}")
    private int chunkOverlap;

    @Value("${document.min-chunk-length:50}")
    private int minChunkLength;

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern SPECIAL_CHARS_PATTERN = Pattern.compile("[^\\w\\s.,!?;:\\-()\\[\\]\"'/\\\\@#$%&+=<>{}|~`*]");

    /**
     * Process a drive document and index it in Solr
     */
    public boolean processDocument(DriveDocument driveDoc, byte[] content) {
        try {
            log.info("Processing document: {}", driveDoc.getName());

            // Extract text based on mime type
            String text = extractText(driveDoc.getMimeType(), content, driveDoc.getName());

            if (text == null || text.trim().isEmpty()) {
                log.warn("No text extracted from document: {}", driveDoc.getName());
                return false;
            }

            // Clean the text
            String cleanedText = cleanText(text);
            log.info("Extracted {} characters from {}", cleanedText.length(), driveDoc.getName());

            // Create chunks
            List<String> chunks = createChunks(cleanedText);
            log.info("Created {} chunks for {}", chunks.size(), driveDoc.getName());

            // Check if any chunks were created
            if (chunks.isEmpty()) {
                log.warn("No chunks created for document {} (text too short: {} chars, min: {})",
                        driveDoc.getName(), cleanedText.length(), minChunkLength);
                return false;
            }

            // Convert to Document objects
            List<Document> documents = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                String chunkContent = chunks.get(i);

                // Prepend document name to chunk for better search
                String contentWithName = "Document: " + driveDoc.getName() + "\n\n" + chunkContent;

                Document doc = Document.builder()
                        .id(driveDoc.getId() + "_" + i)
                        .docId(driveDoc.getId())
                        .docName(driveDoc.getName())
                        .docSource("google_drive")
                        .docType(driveDoc.getMimeType())
                        .url(driveDoc.getWebViewLink())
                        .content(contentWithName)
                        .chunkIndex(i)
                        .modifiedTime(driveDoc.getModifiedTime())
                        .createdTime(driveDoc.getCreatedTime())
                        .build();

                documents.add(doc);
            }

            // Index in Solr
            boolean success = solrSearchService.addDocuments(documents);

            if (success) {
                log.info("Successfully processed {} - {} chunks indexed", driveDoc.getName(), chunks.size());
            } else {
                log.error("Failed to index document: {}", driveDoc.getName());
            }

            return success;

        } catch (Exception e) {
            log.error("Error processing document {}: {}", driveDoc.getName(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Process a Confluence page and index it in Solr
     */
    public boolean processConfluencePage(DriveDocument page) {
        try {
            log.info("Processing Confluence page: {}", page.getName());

            String text = page.getContent();
            if (text == null || text.trim().isEmpty()) {
                log.warn("No content in Confluence page: {}", page.getName());
                return false;
            }

            // Clean the text
            String cleanedText = cleanText(text);
            log.info("Confluence page {} has {} characters", page.getName(), cleanedText.length());

            // Create chunks
            List<String> chunks = createChunks(cleanedText);
            log.info("Created {} chunks for Confluence page {}", chunks.size(), page.getName());

            // Convert to Document objects
            List<Document> documents = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                String chunkContent = chunks.get(i);

                // Prepend document name to chunk for better search
                String contentWithName = "Confluence Page: " + page.getName() + "\n\n" + chunkContent;

                Document doc = Document.builder()
                        .id(page.getId() + "_" + i)
                        .docId(page.getId())
                        .docName(page.getName())
                        .docSource("confluence")
                        .docType("confluence/page")
                        .url(page.getWebViewLink())
                        .content(contentWithName)
                        .chunkIndex(i)
                        .modifiedTime(page.getModifiedTime())
                        .createdTime(page.getCreatedTime())
                        .build();

                documents.add(doc);
            }

            // Index in Solr
            boolean success = solrSearchService.addDocuments(documents);

            if (success) {
                log.info("Successfully processed Confluence page {} - {} chunks indexed", page.getName(), chunks.size());
            } else {
                log.error("Failed to index Confluence page: {}", page.getName());
            }

            return success;

        } catch (Exception e) {
            log.error("Error processing Confluence page {}: {}", page.getName(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Extract text based on document type
     */
    private String extractText(String mimeType, byte[] content, String fileName) {
        try {
            if (mimeType == null) {
                mimeType = guessMimeType(fileName);
            }

            return switch (mimeType) {
                case "application/pdf" -> extractPdfText(content);
                case "application/msword" -> extractDocText(content);
                case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> extractDocxText(content);
                case "application/vnd.ms-excel" -> extractXlsText(content);
                case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> extractXlsxText(content);
                case "text/plain", "text/csv" -> new String(content);
                // Google native formats - exported as Office formats by GoogleDriveService
                case "application/vnd.google-apps.document" -> {
                    log.info("Processing Google Doc (exported as docx): {}", fileName);
                    yield extractDocxText(content);
                }
                case "application/vnd.google-apps.spreadsheet" -> {
                    log.info("Processing Google Sheet (exported as xlsx): {}", fileName);
                    yield extractXlsxText(content);
                }
                default -> {
                    // Try to detect by file extension
                    if (fileName.endsWith(".pdf")) yield extractPdfText(content);
                    else if (fileName.endsWith(".doc")) yield extractDocText(content);
                    else if (fileName.endsWith(".docx")) yield extractDocxText(content);
                    else if (fileName.endsWith(".xls")) yield extractXlsText(content);
                    else if (fileName.endsWith(".xlsx")) yield extractXlsxText(content);
                    else if (fileName.endsWith(".txt") || fileName.endsWith(".csv")) yield new String(content);
                    else {
                        log.warn("Unsupported mime type: {} for file: {}", mimeType, fileName);
                        yield null;
                    }
                }
            };
        } catch (Exception e) {
            log.error("Error extracting text from {}: {}", fileName, e.getMessage());
            return null;
        }
    }

    /**
     * Extract text from PDF
     */
    private String extractPdfText(byte[] content) throws IOException {
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /**
     * Extract text from .doc (old Word format)
     * Falls back to .docx parsing if the file is misnamed
     */
    private String extractDocText(byte[] content) throws IOException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(content);
             HWPFDocument document = new HWPFDocument(bis);
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        } catch (org.apache.poi.poifs.filesystem.OfficeXmlFileException e) {
            // File is a .docx saved with .doc extension, try docx parser
            log.info("File appears to be OOXML format (.docx), trying docx parser");
            return extractDocxText(content);
        } catch (IllegalArgumentException e) {
            // May also indicate wrong format
            log.info("File format mismatch, trying docx parser: {}", e.getMessage());
            return extractDocxText(content);
        } catch (org.apache.poi.poifs.filesystem.NotOLE2FileException e) {
            // File is not a valid OLE2 file, might be plain text or corrupted
            log.warn("File is not a valid Word document (not OLE2 format)");
            // Try as plain text as last resort
            String text = new String(content);
            if (text.length() > 0 && isPrintableText(text)) {
                log.info("Treating as plain text file");
                return text;
            }
            throw e;
        }
    }

    /**
     * Check if text is mostly printable characters
     */
    private boolean isPrintableText(String text) {
        if (text == null || text.isEmpty()) return false;
        int printable = 0;
        int total = Math.min(text.length(), 1000); // Check first 1000 chars
        for (int i = 0; i < total; i++) {
            char c = text.charAt(i);
            if (c >= 32 && c < 127 || c == '\n' || c == '\r' || c == '\t') {
                printable++;
            }
        }
        return (double) printable / total > 0.8; // 80% printable
    }

    /**
     * Extract text from .docx (new Word format)
     */
    private String extractDocxText(byte[] content) throws IOException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(content);
             XWPFDocument document = new XWPFDocument(bis)) {
            StringBuilder text = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                text.append(paragraph.getText()).append("\n");
            }
            return text.toString();
        }
    }

    /**
     * Extract text from .xls (old Excel format)
     * Falls back to .xlsx parsing if the file is misnamed
     */
    private String extractXlsText(byte[] content) throws IOException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(content);
             HSSFWorkbook workbook = new HSSFWorkbook(bis)) {
            return extractWorkbookText(workbook);
        } catch (org.apache.poi.poifs.filesystem.OfficeXmlFileException e) {
            // File is actually .xlsx saved with .xls extension
            log.info("File appears to be OOXML format (.xlsx), trying xlsx parser");
            return extractXlsxText(content);
        } catch (org.apache.poi.poifs.filesystem.NotOLE2FileException e) {
            // File might be CSV or plain text
            log.warn("File is not a valid Excel document (not OLE2 format)");
            String text = new String(content);
            if (text.length() > 0 && isPrintableText(text)) {
                log.info("Treating as plain text/CSV file");
                return text;
            }
            throw e;
        }
    }

    /**
     * Extract text from .xlsx (new Excel format)
     */
    private String extractXlsxText(byte[] content) throws IOException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(content);
             XSSFWorkbook workbook = new XSSFWorkbook(bis)) {
            return extractWorkbookText(workbook);
        }
    }

    /**
     * Extract text from Excel workbook
     */
    private String extractWorkbookText(Workbook workbook) {
        StringBuilder text = new StringBuilder();
        DataFormatter formatter = new DataFormatter();

        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            text.append("Sheet: ").append(sheet.getSheetName()).append("\n");

            for (Row row : sheet) {
                for (Cell cell : row) {
                    String cellValue = formatter.formatCellValue(cell);
                    if (!cellValue.isEmpty()) {
                        text.append(cellValue).append("\t");
                    }
                }
                text.append("\n");
            }
            text.append("\n");
        }
        return text.toString();
    }

    /**
     * Guess mime type from filename
     */
    private String guessMimeType(String fileName) {
        if (fileName == null) return "application/octet-stream";
        fileName = fileName.toLowerCase();

        if (fileName.endsWith(".pdf")) return "application/pdf";
        if (fileName.endsWith(".doc")) return "application/msword";
        if (fileName.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (fileName.endsWith(".xls")) return "application/vnd.ms-excel";
        if (fileName.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (fileName.endsWith(".txt")) return "text/plain";
        if (fileName.endsWith(".csv")) return "text/csv";

        return "application/octet-stream";
    }

    /**
     * Clean extracted text
     */
    private String cleanText(String text) {
        if (text == null) return "";

        // Remove special characters but keep basic punctuation
        text = SPECIAL_CHARS_PATTERN.matcher(text).replaceAll("");

        // Normalize whitespace
        text = WHITESPACE_PATTERN.matcher(text).replaceAll(" ");

        // Remove multiple newlines
        text = text.replaceAll("[\n\r]+", "\n");

        return text.trim();
    }

    /**
     * Create overlapping chunks from text
     */
    private List<String> createChunks(String text) {
        List<String> chunks = new ArrayList<>();

        if (text.length() <= chunkSize) {
            if (text.length() >= minChunkLength) {
                chunks.add(text);
            }
            return chunks;
        }

        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());

            // Try to find a natural break point (end of sentence or paragraph)
            if (end < text.length()) {
                int breakPoint = findBreakPoint(text, start, end);
                if (breakPoint > start) {
                    end = breakPoint;
                }
            }

            String chunk = text.substring(start, end).trim();
            if (chunk.length() >= minChunkLength) {
                chunks.add(chunk);
            }

            // Move start with overlap, ensure progress
            int newStart = end - chunkOverlap;
            if (newStart <= start) {
                newStart = end; // Ensure we always move forward
            }
            start = newStart;
            if (start >= text.length()) break;
        }

        return chunks;
    }

    /**
     * Find a natural break point in text
     */
    private int findBreakPoint(String text, int start, int end) {
        // Look for paragraph break
        int paragraphBreak = text.lastIndexOf("\n\n", end);
        if (paragraphBreak > start + chunkSize / 2) {
            return paragraphBreak + 2;
        }

        // Look for sentence end
        int sentenceEnd = -1;
        for (int i = end - 1; i > start + chunkSize / 2; i--) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && i + 1 < text.length() && Character.isWhitespace(text.charAt(i + 1))) {
                sentenceEnd = i + 1;
                break;
            }
        }
        if (sentenceEnd > 0) {
            return sentenceEnd;
        }

        // Look for line break
        int lineBreak = text.lastIndexOf("\n", end);
        if (lineBreak > start + chunkSize / 2) {
            return lineBreak + 1;
        }

        // Fall back to word boundary
        int space = text.lastIndexOf(" ", end);
        if (space > start + chunkSize / 2) {
            return space + 1;
        }

        return end;
    }
}
