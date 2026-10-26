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

import network.ike.komet.claude.anthropic.AnthropicClient;

/**
 * The per-user configuration that selects and authenticates an {@link LlmProvider}:
 * which service, its credentials, its base URL (for OpenAI-compatible endpoints),
 * and the model to run.
 *
 * <p>Values are normalized on construction: a null/blank {@code id} becomes the
 * default provider ({@link #ANTHROPIC}), so a reader that has never saved a provider
 * selection behaves exactly as the assistant did before the provider layer existed.
 *
 * @param id      the provider id ({@link #ANTHROPIC} or {@link #OPENAI})
 * @param apiKey  the service API key; may be blank when the endpoint needs none
 *                (for example a local Ollama / LM Studio server)
 * @param baseUrl the endpoint base URL; meaningful for {@link #OPENAI}, unused for
 *                {@link #ANTHROPIC} (which has a fixed endpoint)
 * @param model   the model id to run; blank lets the provider use its default
 */
public record ProviderConfig(String id, String apiKey, String baseUrl, String model) {

    /** The Anthropic (Claude) provider id — the historical default. */
    public static final String ANTHROPIC = "anthropic";

    /** The OpenAI-compatible provider id (OpenAI, Azure, Ollama, LM Studio, vLLM, …). */
    public static final String OPENAI = "openai";

    /** The default base URL for the OpenAI-compatible provider. */
    public static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";

    public ProviderConfig {
        if (id == null || id.isBlank()) {
            id = ANTHROPIC;
        }
    }

    /**
     * Whether this configuration can actually make a request.
     *
     * <p>Anthropic requires a non-blank API key. An OpenAI-compatible endpoint only
     * requires a base URL — a local server (Ollama / LM Studio) needs no key, so a blank
     * key is acceptable there.
     *
     * @return {@code true} if the provider can be driven with this configuration
     */
    public boolean usable() {
        return switch (id) {
            case OPENAI -> baseUrl != null && !baseUrl.isBlank() && model != null && !model.isBlank();
            default -> apiKey != null && !apiKey.isBlank();
        };
    }

    /**
     * The model id to use: the configured model, falling back to the provider's default.
     *
     * @return a non-blank model id
     */
    public String resolvedModel() {
        if (model != null && !model.isBlank()) {
            return model;
        }
        return ANTHROPIC.equals(id) ? AnthropicClient.DEFAULT_MODEL : "";
    }

    /**
     * The base URL to use for an OpenAI-compatible endpoint: the configured URL, falling
     * back to {@link #DEFAULT_OPENAI_BASE_URL}. Always blank for the Anthropic provider.
     *
     * @return the base URL, or blank when not applicable
     */
    public String resolvedBaseUrl() {
        if (!OPENAI.equals(id)) {
            return "";
        }
        return (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_OPENAI_BASE_URL : baseUrl;
    }
}
