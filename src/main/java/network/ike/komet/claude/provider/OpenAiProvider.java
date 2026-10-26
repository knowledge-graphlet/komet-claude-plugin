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
package network.ike.komet.claude.provider;

import network.ike.komet.claude.anthropic.AskListener;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.json.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link LlmProvider} for any OpenAI-compatible {@code /chat/completions} endpoint — the
 * OpenAI API, Azure OpenAI, Ollama, LM Studio, vLLM, Groq, DeepSeek, Mistral, LiteLLM, and the
 * like. Hand-rolled over {@link java.net.http.HttpClient} with the vendored
 * {@link network.ike.komet.claude.json.Json json4j}, so it adds no dependency.
 *
 * <p>It runs the same synchronous tool-use loop the assistant has always used, translated to the
 * OpenAI wire format: tools are sent as {@code {type:"function", function:{name, description,
 * parameters}}}, a {@code finish_reason == "tool_calls"} response carries
 * {@code message.tool_calls[]} (each with a JSON-string {@code function.arguments}), and each call
 * is answered with a {@code {role:"tool", tool_call_id, content}} message before the loop continues.
 * A blank API key is accepted (local servers such as Ollama need none) — the {@code Authorization}
 * header is then simply omitted.
 *
 * <p>Like the Anthropic client it is non-streaming, retries transient (429 / 5xx / I/O) failures
 * with the shared {@link HttpRetry} backoff, honours {@code retry-after}, and aborts promptly on
 * thread interruption.
 */
public final class OpenAiProvider implements LlmProvider {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiProvider.class);

    private static final int MAX_ATTEMPTS = HttpRetry.DEFAULT_MAX_ATTEMPTS;
    private static final long BASE_BACKOFF_MILLIS = HttpRetry.DEFAULT_BASE_BACKOFF_MILLIS;
    private static final long MAX_BACKOFF_MILLIS = HttpRetry.DEFAULT_MAX_BACKOFF_MILLIS;
    private static final long MAX_TOTAL_RETRY_MILLIS = HttpRetry.DEFAULT_MAX_TOTAL_RETRY_MILLIS;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final int maxTurns;

    /**
     * Creates the OpenAI-compatible provider.
     *
     * @param apiKey    the API key; may be blank for a local server (no {@code Authorization} sent)
     * @param baseUrl   the endpoint base URL (e.g. {@code https://api.openai.com/v1} or
     *                  {@code http://localhost:11434/v1}); a trailing slash is tolerated
     * @param model     the model id; must be non-blank (OpenAI-compatible servers have no default)
     * @param maxTokens the response token cap; a sane default when not positive
     * @param maxTurns  the tool-use turn cap; a default when not positive
     * @throws LlmProviderException if the base URL or model is blank
     */
    public OpenAiProvider(String apiKey, String baseUrl, String model, int maxTokens, int maxTurns) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new LlmProviderException("No base URL configured for the OpenAI-compatible provider");
        }
        if (model == null || model.isBlank()) {
            throw new LlmProviderException("No model configured for the OpenAI-compatible provider");
        }
        this.apiKey = (apiKey == null) ? "" : apiKey.trim();
        this.baseUrl = trimTrailingSlash(baseUrl.trim());
        this.model = model;
        this.maxTokens = maxTokens > 0 ? maxTokens : 8192;
        this.maxTurns = maxTurns > 0 ? maxTurns : 16;
    }

    @Override
    public String id() {
        return ProviderConfig.OPENAI;
    }

    @Override
    public String displayName() {
        return "OpenAI-compatible";
    }

    @Override
    public String ask(String system, List<AnthropicTool> tools, String userMessage) {
        return ask(system, tools, List.of(), userMessage, AskListener.NOOP, () -> false);
    }

    @Override
    public String ask(String system, List<AnthropicTool> tools,
                      List<Map<String, Object>> priorMessages, String userMessage) {
        return ask(system, tools, priorMessages, userMessage, AskListener.NOOP, () -> false);
    }

    @Override
    public String ask(String system, List<AnthropicTool> tools,
                      List<Map<String, Object>> priorMessages, String userMessage,
                      AskListener listener) {
        return ask(system, tools, priorMessages, userMessage, listener, () -> false);
    }

    /**
     * Runs a complete tool-use exchange against the {@code /chat/completions} endpoint, reporting
     * per-turn / per-tool progress to {@code listener} and honouring {@code earlyStop} after each
     * turn's tools execute. See {@link LlmProvider#ask(String, List, List, String, AskListener,
     * BooleanSupplier)} for the contract.
     */
    @Override
    public String ask(String system, List<AnthropicTool> tools,
                      List<Map<String, Object>> priorMessages, String userMessage,
                      AskListener listener, BooleanSupplier earlyStop) {
        AskListener obs = (listener == null) ? AskListener.NOOP : listener;
        BooleanSupplier stop = (earlyStop == null) ? () -> false : earlyStop;
        Map<String, AnthropicTool> byName = new HashMap<>();
        if (tools != null) {
            for (AnthropicTool t : tools) {
                byName.put(t.name(), t);
            }
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            messages.add(Map.of("role", "system", "content", system));
        }
        if (priorMessages != null) {
            messages.addAll(priorMessages);
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        long start = System.nanoTime();
        int turn = 0;
        try {
            for (turn = 0; turn < maxTurns; turn++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new LlmProviderException("Interrupted before turn " + turn);
                }
                obs.onTurnStart(turn);
                long sent = System.nanoTime();
                Map<String, Object> resp = send(messages, tools, turn, obs);
                obs.onTurnEnd(turn, millisSince(sent), usageOf(resp));

                Map<String, Object> choice = firstChoice(resp);
                Map<String, Object> message = messageOf(choice);
                List<Map<String, Object>> toolCalls = toolCallsOf(message);
                String text = textOf(message);

                if (toolCalls.isEmpty()) {
                    obs.onDone("stop", turn + 1, millisSince(start));
                    return text;
                }

                // Echo the assistant turn (with its tool_calls) back, then answer each call.
                messages.add(assistantTurnMessage(message, toolCalls));
                for (Map<String, Object> call : toolCalls) {
                    messages.add(toolResultMessage(call, byName, turn, obs));
                }
                if (stop.getAsBoolean()) {
                    obs.onDone("tool_complete", turn + 1, millisSince(start));
                    return text;
                }
            }
            obs.onDone("max_turns", maxTurns, millisSince(start));
            return "(stopped: tool-use loop exceeded " + maxTurns + " turns)";
        } catch (RuntimeException e) {
            obs.onError(e, turn, millisSince(start));
            throw e;
        }
    }

    /** Sends one {@code /chat/completions} request (with retry) and returns the parsed body. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> send(List<Map<String, Object>> messages, List<AnthropicTool> tools,
                                     int turn, AskListener obs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", toolDefs(tools));
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofMinutes(2))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body)));
        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response = sendWithRetry(builder.build(), turn, obs);
        int status = response.statusCode();
        if (status / 100 != 2) {
            throw new LlmProviderException("OpenAI-compatible API returned " + status + ": " + response.body());
        }
        return Json.parse(response.body(), Map.class);
    }

    /**
     * POSTs with full-jitter exponential backoff on 429 / 5xx / I/O errors, honouring a
     * {@code retry-after} header. Bounded the same way the Anthropic client is: at most
     * {@link #MAX_ATTEMPTS} attempts, an overall {@link #MAX_TOTAL_RETRY_MILLIS} wall-clock budget,
     * and prompt abort on thread interruption.
     */
    private HttpResponse<String> sendWithRetry(HttpRequest request, int turn, AskListener obs) {
        long deadlineNanos = System.nanoTime() + MAX_TOTAL_RETRY_MILLIS * 1_000_000L;
        LlmProviderException last = null;
        int lastStatus = 0;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new LlmProviderException("Interrupted while contacting the OpenAI-compatible API");
            }
            long retryAfterMillis = -1L;
            String retryReason = "transient error";
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status != 429 && status < 500) {
                    return response;
                }
                lastStatus = status;
                last = new LlmProviderException(describeRetryable(status, attempt));
                retryReason = describeRetryableKind(status);
                retryAfterMillis = HttpRetry.retryAfterMillis(response, Clock.systemUTC(), MAX_TOTAL_RETRY_MILLIS);
            } catch (IOException e) {
                String detail = (e.getMessage() != null) ? e.getMessage() : e.toString();
                last = new LlmProviderException("I/O error contacting the OpenAI-compatible API: " + detail, e);
                retryReason = "Connection problem";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmProviderException("Interrupted while contacting the OpenAI-compatible API", e);
            }
            if (attempt == MAX_ATTEMPTS - 1) {
                break;
            }
            long waitMillis = HttpRetry.backoffWaitMillis(attempt, retryAfterMillis, BASE_BACKOFF_MILLIS,
                    MAX_BACKOFF_MILLIS, ThreadLocalRandom.current());
            long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
            if (waitMillis > remainingMillis) {
                break;
            }
            LOG.warn("{}; retrying in {} ms", last.getMessage(), waitMillis);
            obs.onRetry(turn, attempt + 1, MAX_ATTEMPTS, waitMillis, retryReason);
            if (!sleep(waitMillis)) {
                throw new LlmProviderException("Interrupted during retry backoff");
            }
        }
        throw (last != null) ? last : new LlmProviderException("OpenAI-compatible API request failed");
    }

    /** The condition name for a retryable status (for the status strip). */
    private static String describeRetryableKind(int status) {
        String kind = switch (status) {
            case 429 -> "rate-limited (429)";
            default -> "server error (" + status + ")";
        };
        return "OpenAI-compatible API " + kind;
    }

    /** The condition plus the attempt counter (for the log and the transcript-facing message). */
    private static String describeRetryable(int status, int attempt) {
        return describeRetryableKind(status) + " on attempt " + (attempt + 1) + "/" + MAX_ATTEMPTS;
    }

    // ── Response navigation ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static Map<String, Object> firstChoice(Map<String, Object> resp) {
        Object choices = resp.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new LlmProviderException("OpenAI-compatible response had no choices: " + resp);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> messageOf(Map<String, Object> choice) {
        Object message = choice.get("message");
        if (message instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new LlmProviderException("OpenAI-compatible response choice had no message: " + choice);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> toolCallsOf(Map<String, Object> message) {
        Object toolCalls = message.get("tool_calls");
        if (!(toolCalls instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> calls = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                calls.add((Map<String, Object>) m);
            }
        }
        return calls;
    }

    /** The assistant turn's text content, or an empty string when it is null (a pure tool-call turn). */
    static String textOf(Map<String, Object> message) {
        Object content = message.get("content");
        return (content == null) ? "" : String.valueOf(content);
    }

    /** The assistant message to echo back: its content (possibly null) plus the tool_calls. */
    static Map<String, Object> assistantTurnMessage(Map<String, Object> message,
                                                            List<Map<String, Object>> toolCalls) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", "assistant");
        out.put("content", message.get("content"));
        out.put("tool_calls", toolCalls);
        return out;
    }

    /** Executes one requested tool and builds its {@code {role:"tool"}} result message. */
    static Map<String, Object> toolResultMessage(Map<String, Object> call,
                                                         Map<String, AnthropicTool> byName,
                                                         int turn, AskListener obs) {
        Object id = call.getOrDefault("id", "");
        String callId = String.valueOf(id);
        String name = functionName(call);
        Map<String, Object> input = parseArgs(call);

        obs.onToolCall(turn, name, input);
        long toolStart = System.nanoTime();
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
        obs.onToolResult(turn, name, millisSince(toolStart), isError);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "tool");
        result.put("tool_call_id", callId);
        result.put("content", out);
        return result;
    }

    @SuppressWarnings("unchecked")
    static String functionName(Map<String, Object> call) {
        Object function = call.get("function");
        if (function instanceof Map<?, ?> m) {
            Object name = ((Map<String, Object>) m).get("name");
            if (name != null) {
                return String.valueOf(name);
            }
        }
        return String.valueOf(call.get("name"));
    }

    /** Parses a tool call's {@code function.arguments} (a JSON string) into an input map. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseArgs(Map<String, Object> call) {
        Object function = call.get("function");
        Object rawArgs = (function instanceof Map<?, ?> m) ? ((Map<String, Object>) m).get("arguments") : null;
        if (rawArgs == null) {
            return Map.of();
        }
        if (rawArgs instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        String args = String.valueOf(rawArgs).trim();
        if (args.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = Json.parse(args, Map.class);
            return (parsed == null) ? Map.of() : parsed;
        } catch (RuntimeException malformed) {
            return Map.of();
        }
    }

    /** Maps an OpenAI {@code usage} object to the listener's usage counters. */
    static AskListener.Usage usageOf(Map<String, Object> resp) {
        Object usage = resp.get("usage");
        if (!(usage instanceof Map<?, ?> m)) {
            return new AskListener.Usage(0, 0, 0, 0);
        }
        return new AskListener.Usage(
                intOf(m, "prompt_tokens"),
                intOf(m, "completion_tokens"),
                0, 0);
    }

    private static int intOf(Map<?, ?> usage, String key) {
        Object value = usage.get(key);
        return (value instanceof Number n) ? n.intValue() : 0;
    }

    // ── Tool definitions + small helpers ──────────────────────────────────

    /** Builds the OpenAI {@code tools} array from the provider-neutral tools. */
    static List<Map<String, Object>> toolDefs(List<AnthropicTool> tools) {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (AnthropicTool t : tools) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", t.name());
            function.put("description", t.description());
            function.put("parameters", t.inputSchema());
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", function);
            defs.add(tool);
        }
        return defs;
    }

    private static String trimTrailingSlash(String url) {
        int end = url.length();
        while (end > 1 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }

    /** Elapsed milliseconds since a {@link System#nanoTime()} reading. */
    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * Sleeps for {@code millis}, returning {@code false} (re-asserting the interrupt) if interrupted
     * — so the caller can abort the retry loop at once instead of firing another live request.
     */
    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
