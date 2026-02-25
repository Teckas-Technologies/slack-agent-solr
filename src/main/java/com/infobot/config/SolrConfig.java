package com.infobot.config;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Apache Solr Configuration
 */
@Configuration
public class SolrConfig {

    @Value("${solr.host}")
    private String solrHost;

    @Value("${solr.collection}")
    private String collection;

    @Value("${solr.connection-timeout:10000}")
    private int connectionTimeout;

    @Value("${solr.socket-timeout:60000}")
    private int socketTimeout;

    @Bean
    public SolrClient solrClient() {
        String solrUrl = solrHost + "/" + collection;

        return new Http2SolrClient.Builder(solrUrl)
                .withConnectionTimeout(connectionTimeout, java.util.concurrent.TimeUnit.MILLISECONDS)
                .withRequestTimeout(socketTimeout, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build();
    }
}
