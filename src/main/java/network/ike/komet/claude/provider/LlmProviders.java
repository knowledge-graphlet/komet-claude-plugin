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

import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.PreferencesService;

import java.util.List;
import java.util.Objects;
import java.util.prefs.BackingStoreException;

/**
 * The single place that selects and constructs an {@link LlmProvider} from the per-user
 * configuration. Every assistant surface (the Komet Assistant card, the embeddable chat /
 * check areas, the ANF and semantic lifts, the instruction editor, and the headless commit
 * narrator) obtains its provider here, so the provider selection, credentials, base URL and
 * model are read from one source rather than scattered across the surfaces.
 *
 * <p>Configuration lives in {@link PreferencesService#userPreferences()} (per OS user, never in
 * the knowledge base), under the {@code network.ike.komet.claude.provider.*} keys. The pre-provider
 * Anthropic keys ({@code ...claude.apiKey} / {@code ...claude.model}) are honoured as a fallback for
 * the Anthropic provider, so a user who configured the assistant before the provider layer existed
 * keeps working unchanged.
 */
public final class LlmProviders {

    // ── Provider-aware preference keys (the canonical home for all of them) ──

    /** The selected provider id ({@link ProviderConfig#ANTHROPIC} or {@link ProviderConfig#OPENAI}). */
    public static final String PREF_PROVIDER = "network.ike.komet.claude.provider";

    /** The selected provider's API key. */
    public static final String PREF_PROVIDER_API_KEY = "network.ike.komet.claude.provider.apiKey";

    /** The OpenAI-compatible endpoint base URL (unused for Anthropic). */
    public static final String PREF_PROVIDER_BASE_URL = "network.ike.komet.claude.provider.baseUrl";

    /** The selected provider's model id. */
    public static final String PREF_PROVIDER_MODEL = "network.ike.komet.claude.provider.model";

    // ── Legacy (pre-provider) Anthropic keys — read as a fallback, still written for Anthropic ──

    /** The legacy per-user Anthropic API key (matches the assistant's historical key). */
    public static final String PREF_LEGACY_API_KEY = "network.ike.komet.claude.apiKey";

    /** The legacy per-user model id. */
    public static final String PREF_LEGACY_MODEL = "network.ike.komet.claude.model";

    /** A provider choice offered in the settings UI. */
    public record ProviderOption(String id, String displayName, String defaultBaseUrl, String keyPrompt) {
    }

    /** The providers the assistant can be pointed at, in settings-UI order. */
    public static final List<ProviderOption> OPTIONS = List.of(
            new ProviderOption(ProviderConfig.ANTHROPIC, "Anthropic (Claude)", "", "sk-ant-…"),
            new ProviderOption(ProviderConfig.OPENAI,
                    "OpenAI-compatible (OpenAI, Azure, Ollama, LM Studio, vLLM, …)",
                    ProviderConfig.DEFAULT_OPENAI_BASE_URL, "sk-…  (blank for a local server)"));

    private LlmProviders() {
    }

    /**
     * The provider options, for populating the settings-UI selector.
     *
     * @return the available providers
     */
    public static List<ProviderOption> options() {
        return OPTIONS;
    }

    /**
     * Reads the provider configuration from the per-user preferences, applying defaults and the
     * legacy Anthropic fallback.
     *
     * @return the resolved configuration (never null)
     */
    public static ProviderConfig readConfig() {
        KometPreferences prefs = PreferencesService.userPreferences();
        String provider = normalizeId(prefs.get(PREF_PROVIDER, ""));
        String apiKey = prefs.get(PREF_PROVIDER_API_KEY, "");
        String baseUrl = prefs.get(PREF_PROVIDER_BASE_URL, "");
        String model = prefs.get(PREF_PROVIDER_MODEL, "");

        if (ProviderConfig.ANTHROPIC.equals(provider)) {
            // Fall back to the pre-provider keys so existing setups keep working.
            if (apiKey.isBlank()) {
                apiKey = prefs.get(PREF_LEGACY_API_KEY, "");
            }
            if (model.isBlank()) {
                model = prefs.get(PREF_LEGACY_MODEL, "");
            }
            return new ProviderConfig(ProviderConfig.ANTHROPIC, apiKey, null, model);
        }
        return new ProviderConfig(provider, apiKey, baseUrl, model);
    }

    /**
     * Whether the current per-user configuration can drive a request.
     *
     * @return {@code true} if a provider is usable as configured
     */
    public static boolean isConfigured() {
        return readConfig().usable();
    }

    /**
     * Creates a provider from the current per-user configuration, with default token/turn caps.
     *
     * @return a ready-to-use provider
     */
    public static LlmProvider create() {
        return create(readConfig(), 8192, 16);
    }

    /**
     * Creates a provider from the current per-user configuration.
     *
     * @param maxTokens the response token cap
     * @return a ready-to-use provider
     */
    public static LlmProvider create(int maxTokens) {
        return create(readConfig(), maxTokens, 16);
    }

    /**
     * Creates the {@link LlmProvider} for {@code config} with the default tool-use turn cap.
     *
     * @param config    the resolved configuration
     * @param maxTokens the response token cap
     * @return the provider
     */
    public static LlmProvider create(ProviderConfig config, int maxTokens) {
        return create(config, maxTokens, 16);
    }

    /**
     * Creates the {@link LlmProvider} for {@code config} with explicit token/turn caps.
     *
     * @param config    the resolved configuration
     * @param maxTokens the response token cap; a sane default when not positive
     * @param maxTurns  the tool-use turn cap; a default when not positive
     * @return the provider
     * @throws IllegalArgumentException if {@code config.id()} is not a known provider
     */
    public static LlmProvider create(ProviderConfig config, int maxTokens, int maxTurns) {
        Objects.requireNonNull(config, "config");
        return switch (config.id()) {
            case ProviderConfig.OPENAI ->
                    new OpenAiProvider(config.apiKey(), config.resolvedBaseUrl(), config.model(), maxTokens, maxTurns);
            case ProviderConfig.ANTHROPIC ->
                    new AnthropicProvider(config.apiKey(), config.model(), maxTokens, maxTurns);
            default -> throw new IllegalArgumentException("Unknown LLM provider: " + config.id());
        };
    }

    /**
     * Persists a provider configuration to the per-user preferences.
     *
     * <p>For the Anthropic provider the values are also written to the legacy keys, so the
     * pre-provider reader surfaces (and any tooling that reads them) stay in sync.
     *
     * @param config the configuration to persist
     */
    public static void save(ProviderConfig config) {
        Objects.requireNonNull(config, "config");
        KometPreferences prefs = PreferencesService.userPreferences();
        prefs.put(PREF_PROVIDER, config.id());
        prefs.put(PREF_PROVIDER_API_KEY, config.apiKey() == null ? "" : config.apiKey());
        prefs.put(PREF_PROVIDER_MODEL, config.model() == null ? "" : config.model());
        if (ProviderConfig.OPENAI.equals(config.id())) {
            prefs.put(PREF_PROVIDER_BASE_URL,
                    config.baseUrl() == null || config.baseUrl().isBlank()
                            ? ProviderConfig.DEFAULT_OPENAI_BASE_URL : config.baseUrl());
        }
        if (ProviderConfig.ANTHROPIC.equals(config.id())) {
            // Keep the legacy keys in lock-step for the Anthropic provider.
            String key = (config.apiKey() == null) ? "" : config.apiKey();
            String model = config.model();
            if (!key.isBlank()) {
                prefs.put(PREF_LEGACY_API_KEY, key);
            }
            if (model != null && !model.isBlank()) {
                prefs.put(PREF_LEGACY_MODEL, model);
            }
        }
        try {
            prefs.sync();
        } catch (BackingStoreException ex) {
            // Persisting is best-effort from the UI; the in-memory value still applies this session.
        }
    }

    /** Normalizes a stored provider id, treating blank/unknown as the Anthropic default. */
    private static String normalizeId(String raw) {
        if (raw == null || raw.isBlank()) {
            return ProviderConfig.ANTHROPIC;
        }
        String id = raw.trim();
        return OPTIONS.stream().anyMatch(o -> o.id().equals(id)) ? id : ProviderConfig.ANTHROPIC;
    }
}
