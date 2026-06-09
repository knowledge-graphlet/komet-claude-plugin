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

import java.util.Objects;

/**
 * Connection settings for posting to a Zulip realm as a bot — the realm base
 * URL, the bot's email and API key (used for HTTP Basic auth), and the default
 * stream that concept notifications land in.
 *
 * <p>For v1 these are read from the environment via {@link #fromEnv()}, matching
 * the 1Password {@code op run --env-file} release-secrets pattern; the API key is
 * never embedded. A later revision can move them to per-OS-user
 * {@code KometPreferences} alongside the Anthropic API key.
 *
 * @param baseUrl       the realm base URL, e.g. {@code https://ike.zulipchat.com}
 *                      (any trailing slash is stripped)
 * @param botEmail      the bot account email (Basic-auth username)
 * @param apiKey        the bot API key (Basic-auth password)
 * @param defaultStream the stream concept notifications post to
 */
public record ZulipConfig(String baseUrl, String botEmail, String apiKey, String defaultStream) {

    /** The stream used when none is configured. */
    public static final String DEFAULT_STREAM = "Komet Curation";

    /**
     * Canonicalizes and validates the settings.
     *
     * @throws NullPointerException     if any field is null
     * @throws IllegalArgumentException if {@code baseUrl} is not http(s)
     */
    public ZulipConfig {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(botEmail, "botEmail");
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(defaultStream, "defaultStream");
        baseUrl = baseUrl.strip();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("baseUrl must be an http(s) URL: " + baseUrl);
        }
    }

    /**
     * Reads configuration from environment variables, accepting both the
     * {@code ZULIP_BOT_*} (CI) and {@code ZULIP_*} (MCP server) spellings.
     *
     * <ul>
     *   <li>{@code ZULIP_URL}</li>
     *   <li>{@code ZULIP_BOT_EMAIL} or {@code ZULIP_EMAIL}</li>
     *   <li>{@code ZULIP_BOT_API_KEY} or {@code ZULIP_API_KEY}</li>
     *   <li>{@code ZULIP_DEFAULT_STREAM} (optional; defaults to {@value #DEFAULT_STREAM})</li>
     * </ul>
     *
     * @return the configuration
     * @throws ZulipException if a required variable is missing or blank
     */
    public static ZulipConfig fromEnv() {
        String url = env("ZULIP_URL");
        String email = firstNonBlank(System.getenv("ZULIP_BOT_EMAIL"), System.getenv("ZULIP_EMAIL"));
        String key = firstNonBlank(System.getenv("ZULIP_BOT_API_KEY"), System.getenv("ZULIP_API_KEY"));
        String stream = firstNonBlank(System.getenv("ZULIP_DEFAULT_STREAM"), DEFAULT_STREAM);
        if (url == null || email == null || key == null) {
            throw new ZulipException("Zulip is not configured: set ZULIP_URL, "
                    + "ZULIP_BOT_EMAIL (or ZULIP_EMAIL), and ZULIP_BOT_API_KEY (or ZULIP_API_KEY).");
        }
        return new ZulipConfig(url, email, key, stream);
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? null : v.strip();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.strip();
        }
        return (b == null || b.isBlank()) ? null : b.strip();
    }
}
