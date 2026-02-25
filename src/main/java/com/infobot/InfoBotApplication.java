package com.infobot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * InfoBot - Slack Document Agent with Apache Solr
 *
 * A RAG-based Slack bot that searches Google Drive documents
 * using Apache Solr and generates answers with Gemini AI.
 */
@Slf4j
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class InfoBotApplication {

    public static void main(String[] args) {
        log.info("Starting InfoBot - Slack Document Agent with Apache Solr");
        SpringApplication.run(InfoBotApplication.class, args);
        log.info("✅ InfoBot started successfully!");
    }
}
