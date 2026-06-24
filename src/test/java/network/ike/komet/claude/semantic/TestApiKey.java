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
package network.ike.komet.claude.semantic;

import dev.ikm.komet.preferences.PreferencesService;

import java.util.Optional;

/**
 * Resolves an Anthropic API key for the live integration tests, on a machine-by-machine basis. The
 * key is never committed; a test that needs it skips (via {@code assumeTrue}) when none is found.
 *
 * <p>Resolution order, first non-blank wins:
 * <ol>
 *   <li>the {@code anthropic.api.key} system property (an explicit local/CI override);</li>
 *   <li>the {@code ANTHROPIC_API_KEY} environment variable;</li>
 *   <li>this machine's Komet user preferences — the same per-OS-user key the Claude Assistant
 *       stores ({@code network.ike.komet.claude.apiKey}), so a developer who has configured Komet
 *       already has the key available to the test.</li>
 * </ol>
 *
 * <p>For TeamCity, the plan is to seed the build agent's user preferences with a standard key (or
 * set the system property / environment variable in the build configuration); this helper already
 * supports all three, so no test change is needed when that lands.
 */
final class TestApiKey {

    /** The per-OS-user preference key the Claude Assistant stores the API key under. */
    private static final String PREF_API_KEY = "network.ike.komet.claude.apiKey";

    private TestApiKey() {
    }

    /**
     * Resolves the API key from system property, environment, or Komet user preferences.
     *
     * @return the key if any source provides a non-blank value; otherwise empty
     */
    static Optional<String> resolve() {
        String property = System.getProperty("anthropic.api.key");
        if (notBlank(property)) {
            return Optional.of(property.trim());
        }
        String environment = System.getenv("ANTHROPIC_API_KEY");
        if (notBlank(environment)) {
            return Optional.of(environment.trim());
        }
        try {
            String preference = PreferencesService.userPreferences().get(PREF_API_KEY, "");
            if (notBlank(preference)) {
                return Optional.of(preference.trim());
            }
        } catch (Throwable preferencesUnavailable) {
            // No usable preferences backing store in this JVM; treat as "no key" and let the test skip.
        }
        return Optional.empty();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
