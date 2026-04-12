package me.pacotaco.laby;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Sends Discord embed notifications via a plain webhook URL.
 * No DiscordSRV dependency required.
 */
public class DiscordWebhook {

    // Orange (255, 170, 0) and Green (85, 255, 85) as decimal integers
    private static final int COLOR_MUTE   = 0xFFAA00;
    private static final int COLOR_UNMUTE = 0x55FF55;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final LabyMutePlugin plugin;

    public DiscordWebhook(LabyMutePlugin plugin) {
        this.plugin = plugin;
    }

    public void sendMute(String target, String duration, String reason) {
        String webhookUrl = getWebhookUrl();
        if (webhookUrl == null) return;

        String json = buildEmbed(
                "Voice Muted \u2014 " + target,
                COLOR_MUTE,
                field("Duration", duration, true),
                field("Reason", reason, true)
        );

        post(webhookUrl, json);
    }

    public void sendUnmute(String target, String staff, String reason) {
        String webhookUrl = getWebhookUrl();
        if (webhookUrl == null) return;

        String json = buildEmbed(
                "Voice Unmuted \u2014 " + target,
                COLOR_UNMUTE,
                field("Unmuted by", staff, true),
                field("Reason", reason, true)
        );

        post(webhookUrl, json);
    }

    // -------------------------------------------------------------------------

    private String getWebhookUrl() {
        if (!plugin.getConfig().getBoolean("discord.enabled", false)) return null;
        String url = plugin.getConfig().getString("discord.webhook-url", "");
        if (url == null || url.isBlank()) return null;
        return url;
    }

    private void post(String webhookUrl, String json) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to build Discord webhook request: " + e.getMessage());
            return;
        }
        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenAccept(response -> {
                    int code = response.statusCode();
                    if (code < 200 || code >= 300) {
                        plugin.getLogger().warning("Discord webhook returned HTTP " + code);
                    }
                })
                .exceptionally(e -> {
                    plugin.getLogger().warning("Failed to send Discord webhook: " + e.getMessage());
                    return null;
                });
    }

    // -------------------------------------------------------------------------

    /** Builds a minimal Discord webhook payload with one embed and any number of fields. */
    private String buildEmbed(String title, int color, String... fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"embeds\":[{");
        sb.append("\"title\":").append(jsonString(title)).append(",");
        sb.append("\"color\":").append(color).append(",");
        sb.append("\"timestamp\":\"").append(Instant.now()).append("\",");
        sb.append("\"fields\":[");
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(fields[i]);
        }
        sb.append("]}]}");
        return sb.toString();
    }

    private String field(String name, String value, boolean inline) {
        return "{\"name\":" + jsonString(name) +
               ",\"value\":" + jsonString(value) +
               ",\"inline\":" + inline + "}";
    }

    /** Minimal JSON string escaping. */
    private String jsonString(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\t", "\\t")
                       .replace("\b", "\\b")
                       .replace("\f", "\\f")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r") + "\"";
    }
}
