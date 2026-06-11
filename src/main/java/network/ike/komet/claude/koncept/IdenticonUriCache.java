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
package network.ike.komet.claude.koncept;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

/**
 * A persistent, cross-message cache of a concept's uploaded Zulip identicon URI.
 *
 * <p>Zulip {@code /user_uploads/...} URLs are stable and permanent, and the LifeHash
 * identicon for a concept is deterministic — so each concept's identicon need only be
 * uploaded <em>once, ever</em>, then reused in every message it appears in. Without
 * this, a per-message cache still re-uploads the same identicon in every post, growing
 * Zulip storage without bound. With it, total uploads are bounded by the number of
 * distinct concepts ever referenced.
 *
 * <p>Keyed by {@code realm | conceptUuid} so a {@code /user_uploads} URI from one realm
 * is never reused after the bot is pointed at a different Zulip organization. Backed by
 * a properties file under {@code ~/.config/ike/}; loads lazily, saves on each new entry.
 * All access is synchronized — uploads run on Komet worker threads.
 */
public final class IdenticonUriCache {

    private static final Logger LOG = LoggerFactory.getLogger(IdenticonUriCache.class);

    private static final Path FILE = Path.of(System.getProperty("user.home"),
            ".config", "ike", "zulip-identicon-uploads.properties");

    private static final Properties STORE = new Properties();
    private static boolean loaded = false;

    private IdenticonUriCache() {
    }

    /**
     * Returns the cached upload URI for a concept in a realm, or {@code null} on a miss.
     *
     * @param realm the realm base URL (cache scope)
     * @param uuid  the concept UUID (may be null → miss)
     */
    public static synchronized String get(String realm, UUID uuid) {
        if (uuid == null) {
            return null;
        }
        ensureLoaded();
        return STORE.getProperty(key(realm, uuid));
    }

    /**
     * Records a concept's upload URI for a realm and persists the cache. No-op for a
     * null/blank value.
     */
    public static synchronized void put(String realm, UUID uuid, String uri) {
        if (uuid == null || uri == null || uri.isBlank()) {
            return;
        }
        ensureLoaded();
        STORE.setProperty(key(realm, uuid), uri);
        save();
    }

    private static String key(String realm, UUID uuid) {
        return realm + "|" + uuid;
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (Files.isReadable(FILE)) {
            try (var in = Files.newInputStream(FILE)) {
                STORE.load(in);
                LOG.info("Loaded {} cached Zulip identicon upload(s) from {}", STORE.size(), FILE);
            } catch (IOException e) {
                LOG.warn("Could not read identicon upload cache {}: {}", FILE, e.toString());
            }
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (var out = Files.newOutputStream(FILE)) {
                STORE.store(out, "IKE Komet -> Zulip identicon /user_uploads cache (realm|uuid = uri)");
            }
        } catch (IOException e) {
            LOG.warn("Could not write identicon upload cache {}: {}", FILE, e.toString());
        }
    }
}
