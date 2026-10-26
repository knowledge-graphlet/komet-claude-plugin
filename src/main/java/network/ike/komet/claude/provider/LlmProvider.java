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

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * A pluggable LLM backend the assistant drives: it runs the full synchronous
 * tool-use conversation for one user message and returns the model's final text
 * answer. Implementations wrap a specific service (Anthropic/Claude, an
 * OpenAI-compatible {@code /chat/completions} endpoint, …) and translate the
 * provider-neutral {@link AnthropicTool}s to that service's wire format.
 *
 * <p>The contract is deliberately the one the assistant has always used: the
 * caller supplies a system prompt, the read-only tools, the conversation's prior
 * clean turns, and the new user message, and receives the final answer. Each
 * implementation owns its transport, retry policy, and (where the service offers
 * it) prompt caching; what stays shared is the in-process tool execution and the
 * {@link AskListener} progress events.
 *
 * <p>Obtain a configured instance from {@link LlmProviders} rather than
 * constructing a concrete provider directly, so the per-user provider selection,
 * credentials, base URL and model come from one place.
 */
public interface LlmProvider {

    /**
     * The stable provider id (e.g. {@code "anthropic"}, {@code "openai"}), the
     * value stored under the provider preference and matched in {@link
     * LlmProviders#create(ProviderConfig, int, int)}.
     *
     * @return the provider id
     */
    String id();

    /**
     * A human-readable name for UI (settings panes, status).
     *
     * @return the display name
     */
    String displayName();

    /**
     * Runs a complete tool-use conversation for a single user message.
     *
     * @param system      the system prompt; may be null/blank
     * @param tools       the read-only tools the model may call
     * @param userMessage the user's message text
     * @return the concatenated text of the model's final answer
     * @throws LlmProviderException if the request fails after retries
     */
    String ask(String system, List<AnthropicTool> tools, String userMessage);

    /**
     * Runs a complete tool-use exchange seeded with {@code priorMessages} (the
     * conversation's prior clean user/assistant text turns). Tool-use turns
     * generated during this call are ephemeral — only the prior clean turns carry
     * across calls, which keeps a persisted conversation history valid and compact.
     *
     * @param system        the system prompt; may be null/blank
     * @param tools         the read-only tools the model may call
     * @param priorMessages prior clean turns ({@code {role,content}} maps); may be empty
     * @param userMessage   the new user message text
     * @return the concatenated text of the model's final answer
     * @throws LlmProviderException if the request fails after retries
     */
    String ask(String system, List<AnthropicTool> tools,
               List<Map<String, Object>> priorMessages, String userMessage);

    /**
     * As {@link #ask(String, List, List, String)}, reporting progress to
     * {@code listener}.
     *
     * @param system        the system prompt; may be null/blank
     * @param tools         the read-only tools the model may call
     * @param priorMessages prior clean turns; may be empty
     * @param userMessage   the new user message text
     * @param listener      the progress observer; a no-op when null
     * @return the concatenated text of the model's final answer
     * @throws LlmProviderException if the request fails after retries
     */
    String ask(String system, List<AnthropicTool> tools,
               List<Map<String, Object>> priorMessages, String userMessage,
               AskListener listener);

    /**
     * Runs a complete tool-use exchange and reports per-turn / per-tool progress,
     * wall-clock timing, and usage to {@code listener}.
     *
     * <p>Checks the worker thread's interrupt flag at each turn boundary, so a caller
     * that interrupts the thread (for example to cancel or to shut the area down)
     * aborts the loop rather than running every remaining turn in the background.
     *
     * <p>After each turn's tools run, {@code earlyStop} is polled: when it returns
     * {@code true} the loop ends immediately, without another model round-trip. A
     * tool-driven caller (for example a forced-emit tool that fills a sink) uses this
     * to avoid paying for the model's closing remark once the needed output exists.
     *
     * @param system        the system prompt; may be null/blank
     * @param tools         the read-only tools the model may call
     * @param priorMessages prior clean turns; may be empty
     * @param userMessage   the new user message text
     * @param listener      the progress observer; a no-op when null
     * @param earlyStop     polled after each turn's tools execute; {@code true} ends
     *                      the loop early without a further request
     * @return the concatenated text of the model's final answer
     * @throws LlmProviderException if the request fails after retries or is interrupted
     */
    String ask(String system, List<AnthropicTool> tools,
               List<Map<String, Object>> priorMessages, String userMessage,
               AskListener listener, BooleanSupplier earlyStop);
}
