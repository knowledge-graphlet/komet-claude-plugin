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
import network.ike.komet.claude.anthropic.AnthropicClient;
import network.ike.komet.claude.anthropic.AnthropicTool;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The Anthropic (Claude) {@link LlmProvider}: a thin wrapper over the existing
 * {@link AnthropicClient}, so the historical behaviour — endpoint, headers, prompt
 * caching, retry policy, tool-use loop — is preserved byte-for-byte behind the
 * {@link LlmProvider} seam. This is the default provider and the one every assistant
 * surface used before the provider layer existed.
 */
public final class AnthropicProvider implements LlmProvider {

    private final AnthropicClient client;

    /**
     * Creates the Anthropic provider.
     *
     * @param apiKey    the Anthropic API key (sent as {@code x-api-key})
     * @param model     the model id; {@link AnthropicClient#DEFAULT_MODEL} when null/blank
     * @param maxTokens the response token cap
     * @param maxTurns  the tool-use turn cap
     */
    public AnthropicProvider(String apiKey, String model, int maxTokens, int maxTurns) {
        this.client = new AnthropicClient(apiKey, model, maxTokens, maxTurns);
    }

    @Override
    public String id() {
        return ProviderConfig.ANTHROPIC;
    }

    @Override
    public String displayName() {
        return "Anthropic (Claude)";
    }

    @Override
    public String ask(String system, List<AnthropicTool> tools, String userMessage) {
        return client.ask(system, tools, userMessage);
    }

    @Override
    public String ask(String system, List<AnthropicTool> tools,
                      List<Map<String, Object>> priorMessages, String userMessage) {
        return client.ask(system, tools, priorMessages, userMessage);
    }

    @Override
    public String ask(String system, List<AnthropicTool> tools,
                      List<Map<String, Object>> priorMessages, String userMessage,
                      AskListener listener) {
        return client.ask(system, tools, priorMessages, userMessage, listener);
    }

    @Override
    public String ask(String system, List<AnthropicTool> tools,
                      List<Map<String, Object>> priorMessages, String userMessage,
                      AskListener listener, BooleanSupplier earlyStop) {
        return client.ask(system, tools, priorMessages, userMessage, listener, earlyStop);
    }
}
