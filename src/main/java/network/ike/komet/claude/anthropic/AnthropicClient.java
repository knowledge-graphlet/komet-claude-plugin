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
package network.ike.komet.claude.anthropic;

import network.ike.komet.claude.json.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A minimal, hand-rolled client for the Anthropic Messages API
 * ({@code POST /v1/messages}) over {@link java.net.http.HttpClient}, with no
 * external SDK. JSON (de)serialization is via the vendored
 * {@link network.ike.komet.claude.json.Json json4j} library.
 *
 * <p>{@link #ask(String, List, String)} runs the full synchronous tool-use
 * loop: it sends the conversation, and while Claude responds with
 * {@code stop_reason == "tool_use"} it executes the requested
 * {@link AnthropicTool}s in-process, feeds the {@code tool_result}s back, and
 * repeats until Claude returns a final text answer.
 *
 * <p>The system prompt and tool definitions are marked with
 * {@code cache_control: ephemeral} so the stable prefix is prompt-cached.
 * Non-streaming; streaming may be layered on later.
 */
public final class AnthropicClient {

    /** The Anthropic Messages API endpoint. */
    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";

    /** The {@code anthropic-version} header value. */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** Default model id when none is configured: Claude Sonnet 4.6. */
    public static final String DEFAULT_MODEL = "claude-sonnet-4-6";

    /** Upper bound on tool-use iterations before giving up. */
    private static final int MAX_TURNS = 16;

    /** Retry attempts for transient (429 / 5xx / I/O) failures. */
    private static final int MAX_ATTEMPTS = 4;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final String apiKey;
    private final String model;
    private final int maxTokens;

    /**
     * Creates a client.
     *
     * @param apiKey    the Anthropic API key (sent as {@code x-api-key})
     * @param model     the model id; {@link #DEFAULT_MODEL} when null/blank
     * @param maxTokens the response token cap; a sane default when not positive
     */
    public AnthropicClient(String apiKey, String model, int maxTokens) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
        this.maxTokens = maxTokens > 0 ? maxTokens : 8192;
    }

    /**
     * The subset of the Messages API response this client consumes.
     *
     * @param id          the message id
     * @param role        the message role (always {@code assistant})
     * @param stop_reason  why generation stopped ({@code end_turn},
     *                     {@code tool_use}, {@code max_tokens}, {@code refusal}, …)
     * @param content     the ordered content blocks (text and/or tool_use)
     * @param usage       token-usage counters (incl. cache read/creation)
     */
    public record Response(String id,
                           String role,
                           String stop_reason,
                           List<Map<String, Object>> content,
                           Map<String, Object> usage) {
    }

    /**
     * Runs a complete tool-use conversation for a single user message and
     * returns Claude's final text answer.
     *
     * @param system      the system prompt (cached); may be null/blank
     * @param tools       the read-only tools Claude may call
     * @param userMessage the user's message text
     * @return the concatenated text of Claude's final answer
     * @throws AnthropicException if the API call fails after retries
     */
    public String ask(String system, List<AnthropicTool> tools, String userMessage) {
        Map<String, AnthropicTool> byName = new HashMap<>();
        if (tools != null) {
            for (AnthropicTool t : tools) {
                byName.put(t.name(), t);
            }
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", userMessage));

        for (int turn = 0; turn < MAX_TURNS; turn++) {
            Response resp = send(system, tools, messages);
            messages.add(Map.of("role", "assistant", "content", resp.content()));

            if (!"tool_use".equals(resp.stop_reason())) {
                return textOf(resp.content());
            }
            messages.add(Map.of("role", "user", "content", toolResults(resp.content(), byName)));
        }
        return "(stopped: tool-use loop exceeded " + MAX_TURNS + " turns)";
    }

    /** Executes every {@code tool_use} block and builds the {@code tool_result} list. */
    private static List<Map<String, Object>> toolResults(List<Map<String, Object>> content,
                                                         Map<String, AnthropicTool> byName) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> block : content) {
            if (!"tool_use".equals(block.get("type"))) {
                continue;
            }
            String id = String.valueOf(block.get("id"));
            String name = String.valueOf(block.get("name"));
            Object rawInput = block.getOrDefault("input", Map.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> input = rawInput instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : Map.of();

            String out;
            boolean isError = false;
            AnthropicTool tool = byName.get(name);
            if (tool == null) {
                out = "Unknown tool: " + name;
                isError = true;
            } else {
                try {
                    out = tool.execute(input);
                } catch (RuntimeException e) {
                    out = "Tool error: " + e.getMessage();
                    isError = true;
                }
            }

            Map<String, Object> tr = new LinkedHashMap<>();
            tr.put("type", "tool_result");
            tr.put("tool_use_id", id);
            tr.put("content", out);
            if (isError) {
                tr.put("is_error", Boolean.TRUE);
            }
            results.add(tr);
        }
        return results;
    }

    /** Sends one request and returns the parsed response. */
    private Response send(String system, List<AnthropicTool> tools, List<Map<String, Object>> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        if (system != null && !system.isBlank()) {
            body.put("system", List.of(cacheableText(system)));
        }
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", toolDefs(tools));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofMinutes(2))
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body)))
                .build();

        HttpResponse<String> response = sendWithRetry(request);
        int status = response.statusCode();
        if (status / 100 != 2) {
            throw new AnthropicException("Anthropic API returned " + status + ": " + response.body());
        }
        return Json.parse(response.body(), Response.class);
    }

    /** POSTs with exponential backoff on 429 / 5xx / I/O errors. */
    private HttpResponse<String> sendWithRetry(HttpRequest request) {
        AnthropicException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 429 || status >= 500) {
                    last = new AnthropicException("retryable status " + status);
                    backoff(attempt);
                    continue;
                }
                return response;
            } catch (IOException e) {
                last = new AnthropicException("I/O error contacting Anthropic API: " + e.getMessage(), e);
                backoff(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AnthropicException("Interrupted while contacting Anthropic API", e);
            }
        }
        throw (last != null) ? last : new AnthropicException("Anthropic API request failed");
    }

    /** Builds the {@code tools} array; caches the (stable) tool-list prefix. */
    private static List<Map<String, Object>> toolDefs(List<AnthropicTool> tools) {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (int i = 0; i < tools.size(); i++) {
            AnthropicTool t = tools.get(i);
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("name", t.name());
            def.put("description", t.description());
            def.put("input_schema", t.inputSchema());
            if (i == tools.size() - 1) {
                def.put("cache_control", Map.of("type", "ephemeral"));
            }
            defs.add(def);
        }
        return defs;
    }

    /** A cacheable system text block. */
    private static Map<String, Object> cacheableText(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        block.put("cache_control", Map.of("type", "ephemeral"));
        return block;
    }

    /** Concatenates the {@code text} blocks of a content array. */
    private static String textOf(List<Map<String, Object>> content) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> block : content) {
            if ("text".equals(block.get("type"))) {
                Object text = block.get("text");
                if (text != null) {
                    sb.append(text);
                }
            }
        }
        return sb.toString();
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep((long) (Math.pow(2, attempt) * 500L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
