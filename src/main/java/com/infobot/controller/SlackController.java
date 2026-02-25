package com.infobot.controller;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.infobot.service.QueryEngineService;
import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slack Events Controller
 */
@Slf4j
@RestController
@RequestMapping("/slack")
@RequiredArgsConstructor
public class SlackController {

    private final QueryEngineService queryEngineService;

    @Value("${slack.bot-token:}")
    private String botToken;

    @Value("${slack.signing-secret:}")
    private String signingSecret;

    private MethodsClient slackClient;
    private String botUserId;
    private Gson gson;

    // Deduplication cache for event IDs
    private final Set<String> processedEvents = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> eventTimestamps = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        this.gson = new Gson();

        if (botToken == null || botToken.isEmpty()) {
            log.warn("Slack bot token not configured");
            return;
        }

        Slack slack = Slack.getInstance();
        slackClient = slack.methods(botToken);

        // Get bot user ID
        try {
            var authResponse = slackClient.authTest(r -> r);
            if (authResponse.isOk()) {
                botUserId = authResponse.getUserId();
                log.info("✅ Slack connected as bot user: {} ({})", authResponse.getUser(), botUserId);
            } else {
                log.error("Slack auth failed: {}", authResponse.getError());
            }
        } catch (IOException | SlackApiException e) {
            log.error("Failed to initialize Slack: {}", e.getMessage());
        }
    }

    /**
     * Handle Slack events
     */
    @PostMapping("/events")
    public ResponseEntity<?> handleEvents(@RequestBody String body) {
        try {
            JsonObject payload = gson.fromJson(body, JsonObject.class);

            // Handle URL verification challenge
            if (payload.has("challenge")) {
                String challenge = payload.get("challenge").getAsString();
                log.info("Responding to Slack URL verification challenge");
                return ResponseEntity.ok(Map.of("challenge", challenge));
            }

            // Handle events
            if (payload.has("event")) {
                JsonObject event = payload.getAsJsonObject("event");
                String eventType = event.get("type").getAsString();

                // Deduplicate events
                String eventId = payload.has("event_id") ? payload.get("event_id").getAsString() : "";
                if (!eventId.isEmpty() && processedEvents.contains(eventId)) {
                    log.debug("Ignoring duplicate event: {}", eventId);
                    return ResponseEntity.ok().build();
                }
                processedEvents.add(eventId);

                // Clean old events periodically
                cleanOldEvents();

                // Handle different event types
                switch (eventType) {
                    case "app_mention" -> handleAppMention(event);
                    case "message" -> handleMessage(event);
                    default -> log.debug("Ignoring event type: {}", eventType);
                }
            }

            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("Error handling Slack event: {}", e.getMessage(), e);
            return ResponseEntity.ok().build(); // Always return 200 to Slack
        }
    }

    /**
     * Handle app mention events (@InfoBot)
     */
    private void handleAppMention(JsonObject event) {
        String text = event.has("text") ? event.get("text").getAsString() : "";
        String channel = event.has("channel") ? event.get("channel").getAsString() : "";
        String user = event.has("user") ? event.get("user").getAsString() : "";
        String ts = event.has("ts") ? event.get("ts").getAsString() : "";

        // Remove bot mention from text
        String query = text.replaceAll("<@" + botUserId + ">", "").trim();

        if (query.isEmpty()) {
            sendMessage(channel, "Hello! I'm InfoBot. How can I help you? Ask me anything about your documents!", ts);
            return;
        }

        log.info("Received mention from user {} in channel {}: {}", user, channel, truncate(query, 100));

        // Process asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                // Show typing indicator
                // Note: Slack doesn't have a direct "typing" API for bots

                // Process query
                String response = queryEngineService.processQuery(query);

                // Send response
                sendMessage(channel, response, ts);

            } catch (Exception e) {
                log.error("Error processing mention: {}", e.getMessage(), e);
                sendMessage(channel, "Sorry, I encountered an error processing your request: " + e.getMessage(), ts);
            }
        });
    }

    /**
     * Handle direct messages
     */
    private void handleMessage(JsonObject event) {
        // Ignore bot messages
        if (event.has("bot_id") || (event.has("subtype") && event.get("subtype").getAsString().equals("bot_message"))) {
            return;
        }

        // Check if it's a DM (channel type starting with 'D')
        String channelType = event.has("channel_type") ? event.get("channel_type").getAsString() : "";
        if (!"im".equals(channelType)) {
            return; // Only handle DMs
        }

        String text = event.has("text") ? event.get("text").getAsString() : "";
        String channel = event.has("channel") ? event.get("channel").getAsString() : "";
        String user = event.has("user") ? event.get("user").getAsString() : "";
        String ts = event.has("ts") ? event.get("ts").getAsString() : "";

        if (text.isEmpty()) {
            return;
        }

        log.info("Received DM from user {}: {}", user, truncate(text, 100));

        // Process asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                String response = queryEngineService.processQuery(text);
                sendMessage(channel, response, ts);
            } catch (Exception e) {
                log.error("Error processing DM: {}", e.getMessage(), e);
                sendMessage(channel, "Sorry, I encountered an error: " + e.getMessage(), ts);
            }
        });
    }

    /**
     * Send message to Slack channel
     */
    private void sendMessage(String channel, String text, String threadTs) {
        if (slackClient == null) {
            log.error("Slack client not initialized");
            return;
        }

        try {
            var response = slackClient.chatPostMessage(r -> r
                    .channel(channel)
                    .text(text)
                    .threadTs(threadTs) // Reply in thread
            );

            if (!response.isOk()) {
                log.error("Failed to send Slack message: {}", response.getError());
            }

        } catch (IOException | SlackApiException e) {
            log.error("Error sending Slack message: {}", e.getMessage());
        }
    }

    /**
     * Clean old processed events
     */
    private void cleanOldEvents() {
        if (processedEvents.size() > 1000) {
            // Keep only recent events
            processedEvents.clear();
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "slackConnected", slackClient != null && botUserId != null,
                "botUserId", botUserId != null ? botUserId : "not connected"
        ));
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return "";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }
}
