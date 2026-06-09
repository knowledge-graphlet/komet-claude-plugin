/*
 * Copyright © 2026 Knowledge Graphlet / IKE Network
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.komet.claude.zulip;

import network.ike.komet.claude.json.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A minimal, hand-rolled client for the Zulip REST API
 * ({@code /api/v1/messages}) over {@link java.net.http.HttpClient}, with no
 * external SDK — the same shape as the plugin's
 * {@link network.ike.komet.claude.anthropic.AnthropicClient}. The realm's
 * TypeScript {@code zulip-mcp-server} {@code ZulipClient} is the reference.
 *
 * <p>This is the <em>transport</em> rung of the Zulip per-medium adapter: it
 * sends already-rendered message content and reads a topic back. Rendering lives
 * in {@link ZulipNotifier}. The surface is outbound-only and best-effort, with
 * exponential backoff on {@code 429}/{@code 5xx}/I/O.
 *
 * <p>Auth is HTTP Basic ({@code bot-email:api-key}); the send endpoint takes a
 * {@code application/x-www-form-urlencoded} body, and the read endpoint takes a
 * JSON {@code narrow} query parameter — both per the Zulip API.
 */
public final class ZulipClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final ZulipConfig config;
    private final String authHeader;

    /** Retry attempts for transient (429 / 5xx / I/O) failures. */
    private static final int MAX_ATTEMPTS = 4;

    /**
     * Creates a client.
     *
     * @param config the realm and bot settings; must not be null
     */
    public ZulipClient(ZulipConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        String basic = config.botEmail() + ":" + config.apiKey();
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString(basic.getBytes(StandardCharsets.UTF_8));
    }

    /** The parsed result of a successful {@code POST /messages}. */
    private record SendResult(String result, String msg, Long id) {
    }

    /** The parsed result of a {@code GET /messages}. */
    private record GetResult(String result, String msg, List<Map<String, Object>> messages) {
    }

    /** The parsed result of a {@code POST /user_uploads}. */
    private record UploadResult(String result, String msg, String uri) {
    }

    /**
     * Posts a message to a stream topic.
     *
     * @param channel the stream and topic to post to
     * @param content the message body in Zulip-flavored Markdown (GFM)
     * @return the new message's id
     * @throws ZulipException on a non-2xx response or transport failure after retries
     */
    public long postMessage(ZulipChannel channel, String content) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(content, "content");
        String form = "type=stream"
                + "&to=" + enc(channel.stream())
                + "&topic=" + enc(channel.topic())
                + "&content=" + enc(content);
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/v1/messages"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", authHeader)
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = sendWithRetry(request);
        requireSuccess(response, "send message");
        SendResult result = Json.parse(response.body(), SendResult.class);
        if (result.id() == null) {
            throw new ZulipException("Zulip send returned no message id: " + response.body());
        }
        return result.id();
    }

    /**
     * Uploads a file (e.g. a Koncept identicon PNG) to Zulip and returns the
     * realm-relative {@code /user_uploads/...} URI, which can be embedded directly
     * in a Markdown message as {@code ![alt](uri)}.
     *
     * @param filename    the upload filename (informational)
     * @param data        the file bytes
     * @param contentType the MIME type, e.g. {@code image/png}
     * @return the {@code /user_uploads/...} URI Zulip assigns
     * @throws ZulipException on a non-2xx response or transport failure after retries
     */
    public String uploadFile(String filename, byte[] data, String contentType) {
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(contentType, "contentType");
        String boundary = "ikeKometZulipBoundary"
                + Integer.toHexString(filename.hashCode()) + Integer.toHexString(data.length);
        byte[] body = multipartFile(boundary, "file", filename, contentType, data);
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/v1/user_uploads"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", authHeader)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response = sendWithRetry(request);
        requireSuccess(response, "upload file");
        UploadResult result = Json.parse(response.body(), UploadResult.class);
        if (result.uri() == null || result.uri().isBlank()) {
            throw new ZulipException("Zulip upload returned no uri: " + response.body());
        }
        return result.uri();
    }

    /** Builds a single-part {@code multipart/form-data} body for a file upload. */
    private static byte[] multipartFile(String boundary, String name, String filename,
                                        String contentType, byte[] data) {
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + data.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(data, 0, body, head.length, data.length);
        System.arraycopy(tail, 0, body, head.length + data.length, tail.length);
        return body;
    }

    /**
     * Registers (or confirms) a realm <em>custom emoji</em> from a PNG, so it can be
     * referenced inline in messages as {@code :name:}. Idempotent: an already-existing
     * emoji is treated as success (the Koncept identicon is deterministic, so an
     * existing emoji of the same name is already the correct image).
     *
     * @param emojiName a Zulip-valid emoji name (lowercase letters/digits/{@code _}/{@code -})
     * @param pngData   the emoji image bytes (PNG)
     * @throws ZulipException if the realm rejects the upload (e.g. the bot lacks the
     *                        "add custom emoji" permission, an invalid name, or the
     *                        emoji limit is reached) — the caller falls back to a block image
     */
    public void upsertEmoji(String emojiName, byte[] pngData) {
        Objects.requireNonNull(emojiName, "emojiName");
        Objects.requireNonNull(pngData, "pngData");
        String boundary = "ikeKometEmoji"
                + Integer.toHexString(emojiName.hashCode()) + Integer.toHexString(pngData.length);
        byte[] body = multipartFile(boundary, "file", emojiName + ".png", "image/png", pngData);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(config.baseUrl() + "/api/v1/realm/emoji/" + enc(emojiName)))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", authHeader)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response = sendWithRetry(request);
        if (response.statusCode() / 100 == 2) {
            return;
        }
        String responseBody = response.body() == null ? "" : response.body();
        if (responseBody.contains("already exists")) {
            return; // idempotent — the existing emoji is the same deterministic identicon
        }
        throw new ZulipException("Zulip emoji upsert returned "
                + response.statusCode() + ": " + responseBody);
    }

    /**
     * Reads the most recent messages in a stream topic (newest-anchored).
     *
     * @param channel   the stream and topic to read
     * @param numBefore how many messages before the anchor to fetch (at least 1)
     * @return the raw message objects, oldest-first as Zulip returns them
     * @throws ZulipException on a non-2xx response or transport failure after retries
     */
    public List<Map<String, Object>> topicMessages(ZulipChannel channel, int numBefore) {
        Objects.requireNonNull(channel, "channel");
        String narrow = Json.stringify(List.of(
                Map.of("operator", "stream", "operand", channel.stream()),
                Map.of("operator", "topic", "operand", channel.topic())));
        String query = "?anchor=newest&num_after=0"
                + "&num_before=" + Math.max(1, numBefore)
                + "&narrow=" + enc(narrow);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(config.baseUrl() + "/api/v1/messages" + query))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", authHeader)
                .GET()
                .build();
        HttpResponse<String> response = sendWithRetry(request);
        requireSuccess(response, "get messages");
        GetResult result = Json.parse(response.body(), GetResult.class);
        return result.messages() == null ? List.of() : result.messages();
    }

    private void requireSuccess(HttpResponse<String> response, String what) {
        if (response.statusCode() / 100 != 2) {
            throw new ZulipException("Zulip " + what + " returned "
                    + response.statusCode() + ": " + response.body());
        }
    }

    /** Sends with exponential backoff on 429 / 5xx / I/O errors. */
    private HttpResponse<String> sendWithRetry(HttpRequest request) {
        ZulipException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 429 || status >= 500) {
                    last = new ZulipException("retryable status " + status);
                    backoff(attempt);
                    continue;
                }
                return response;
            } catch (IOException e) {
                last = new ZulipException("I/O error contacting Zulip API: " + e.getMessage(), e);
                backoff(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ZulipException("Interrupted while contacting Zulip API", e);
            }
        }
        throw (last != null) ? last : new ZulipException("Zulip API request failed");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep((long) (Math.pow(2, attempt) * 500L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
