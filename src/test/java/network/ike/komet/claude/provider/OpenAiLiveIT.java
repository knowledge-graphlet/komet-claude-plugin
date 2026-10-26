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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live end-to-end check against a real OpenAI-compatible {@code /chat/completions} endpoint —
 * OpenAI, Azure, Ollama, LM Studio, vLLM, Groq, DeepSeek, Mistral, LiteLLM, or the like. It is
 * tagged {@code external} (excluded from the default build) and skips on any machine without the
 * settings below, mirroring the Anthropic live tests' key-resolution discipline.
 *
 * <p>Configuration (system property wins over environment variable):
 * <ul>
 *   <li>{@code -Dopenai.model} / {@code OPENAI_MODEL} — <em>required</em>, e.g.
 *       {@code gpt-4o-mini} or {@code llama3} for a local server;</li>
 *   <li>{@code -Dopenai.base.url} / {@code OPENAI_BASE_URL} — defaults to
 *       {@code https://api.openai.com/v1} (e.g. {@code http://localhost:11434/v1} for Ollama);</li>
 *   <li>{@code -Dopenai.api.key} / {@code OPENAI_API_KEY} — optional; leave unset for local
 *       servers that need no key.</li>
 * </ul>
 */
@Tag("external")
class OpenAiLiveIT {

    @Test
    void roundTripsARealEndpoint() {
        String model = resolve("openai.model", "OPENAI_MODEL", "");
        assumeTrue(!model.isBlank(),
                "no OpenAI-compatible model configured (set OPENAI_MODEL to run this test)");
        String baseUrl = resolve("openai.base.url", "OPENAI_BASE_URL", ProviderConfig.DEFAULT_OPENAI_BASE_URL);
        String apiKey = resolve("openai.api.key", "OPENAI_API_KEY", "");

        OpenAiProvider provider = new OpenAiProvider(apiKey, baseUrl, model, 256, 1);
        String reply = provider.ask("You answer in one short sentence.", List.of(),
                "What is one word for the feeling after a good walk?");

        assertFalse(reply == null || reply.isBlank(), "empty reply from " + baseUrl);
    }

    /** Resolves a setting from the system property, then the environment variable, then the default. */
    private static String resolve(String property, String env, String defaultValue) {
        String value = System.getProperty(property);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        value = System.getenv(env);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return defaultValue;
    }
}
