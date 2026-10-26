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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Store-free unit tests for the provider configuration model ({@link ProviderConfig}) and the
 * {@link LlmProviders} factory dispatch. The preference-backed readers/writers
 * ({@code readConfig} / {@code save} / {@code isConfigured}) need the Komet preferences runtime
 * and are exercised through the assistant's own settings flow instead.
 */
class LlmProvidersTest {

    @Test
    void nullOrBlankIdDefaultsToAnthropic() {
        assertEquals(ProviderConfig.ANTHROPIC, new ProviderConfig(null, "k", null, "").id());
        assertEquals(ProviderConfig.ANTHROPIC, new ProviderConfig("", "k", null, "").id());
        assertEquals(ProviderConfig.OPENAI, new ProviderConfig(ProviderConfig.OPENAI, "", "", "").id());
    }

    @Test
    void anthropicUsabilityRequiresAKey() {
        assertFalse(new ProviderConfig(ProviderConfig.ANTHROPIC, "", null, "").usable());
        assertFalse(new ProviderConfig(ProviderConfig.ANTHROPIC, "   ", null, "").usable());
        assertFalse(new ProviderConfig(ProviderConfig.ANTHROPIC, null, null, "").usable());
        assertTrue(new ProviderConfig(ProviderConfig.ANTHROPIC, "sk-ant-x", null, "").usable());
    }

    @Test
    void openAiUsabilityRequiresBaseUrlAndModelButNotAKey() {
        // A local server (Ollama / LM Studio) needs no key — just an endpoint and a model.
        assertFalse(new ProviderConfig(ProviderConfig.OPENAI, "", "http://localhost:11434/v1", "").usable());
        assertFalse(new ProviderConfig(ProviderConfig.OPENAI, "", "", "gpt-4o").usable());
        assertTrue(new ProviderConfig(ProviderConfig.OPENAI, "", "http://localhost:11434/v1", "llama3").usable());
        assertTrue(new ProviderConfig(
                ProviderConfig.OPENAI, "sk-x", "https://api.openai.com/v1", "gpt-4o").usable());
    }

    @Test
    void resolvedModelFallsBackPerProvider() {
        assertEquals(AnthropicClient.DEFAULT_MODEL,
                new ProviderConfig(ProviderConfig.ANTHROPIC, "k", null, "").resolvedModel());
        assertEquals("claude-sonnet-4-6",
                new ProviderConfig(ProviderConfig.ANTHROPIC, "k", null, "claude-sonnet-4-6").resolvedModel());
        // OpenAI-compatible servers have no default model: a blank one stays blank.
        assertEquals("",
                new ProviderConfig(ProviderConfig.OPENAI, "k", "https://api.openai.com/v1", "").resolvedModel());
    }

    @Test
    void resolvedBaseUrlAppliesOnlyToOpenAi() {
        assertEquals("",
                new ProviderConfig(ProviderConfig.ANTHROPIC, "k", null, "").resolvedBaseUrl());
        assertEquals(ProviderConfig.DEFAULT_OPENAI_BASE_URL,
                new ProviderConfig(ProviderConfig.OPENAI, "k", "", "gpt-4o").resolvedBaseUrl());
        assertEquals("http://localhost:11434/v1",
                new ProviderConfig(ProviderConfig.OPENAI, "k", "http://localhost:11434/v1", "llama3")
                        .resolvedBaseUrl());
    }

    @Test
    void createDispatchesByProviderId() {
        LlmProvider anthropic = LlmProviders.create(
                new ProviderConfig(ProviderConfig.ANTHROPIC, "sk-ant-x", null, ""), 100);
        assertInstanceOf(AnthropicProvider.class, anthropic);

        LlmProvider openAi = LlmProviders.create(
                new ProviderConfig(ProviderConfig.OPENAI, "sk-x", "https://api.openai.com/v1", "gpt-4o"), 100);
        assertInstanceOf(OpenAiProvider.class, openAi);
    }

    @Test
    void createSurfacesConstructorFailuresForUnusableOpenAiConfigs() {
        // A blank model is not recoverable: the constructor rejects it.
        assertThrows(LlmProviderException.class, () -> LlmProviders.create(
                new ProviderConfig(ProviderConfig.OPENAI, "", "https://api.openai.com/v1", ""), 100));
        // A blank base URL is recoverable: create() resolves the default endpoint for it.
        LlmProvider provider = LlmProviders.create(
                new ProviderConfig(ProviderConfig.OPENAI, "sk-x", "", "gpt-4o"), 100);
        assertInstanceOf(OpenAiProvider.class, provider);
    }

    @Test
    void createRejectsUnknownProviderIds() {
        assertThrows(IllegalArgumentException.class,
                () -> LlmProviders.create(new ProviderConfig("weird", "k", null, "m"), 100));
    }

    @Test
    void optionsExposeBothProvidersInOrder() {
        assertEquals(2, LlmProviders.options().size());
        assertEquals(ProviderConfig.ANTHROPIC, LlmProviders.options().get(0).id());
        assertEquals(ProviderConfig.OPENAI, LlmProviders.options().get(1).id());
        for (LlmProviders.ProviderOption option : LlmProviders.options()) {
            assertFalse(option.displayName().isBlank());
        }
    }
}
