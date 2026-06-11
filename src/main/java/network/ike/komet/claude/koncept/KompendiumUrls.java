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

import java.net.URI;
import java.util.UUID;

/**
 * Stub for the <strong>Kompendium URL</strong> scheme — the durable, web-addressable
 * home of a concept (a "Koncept"): its entry page and its hosted LifeHash identicon.
 *
 * <p>This is the link target a Koncept reference resolves to across media — the adoc
 * {@code k:} badge's click, the komet concept-chip's click, and the Zulip post's
 * label — replacing today's per-document {@code #koncept-<id>} anchors with one
 * durable external entry. It also lets every renderer reference the <em>same</em>
 * hosted identicon ({@link #identiconUrl}) instead of each re-embedding it.
 *
 * <p>The Kompendium site that serves these URLs is not built yet; this establishes
 * the <em>scheme</em> and the <em>join key</em> (the concept's first {@code PublicId}
 * UUID) so every renderer can link consistently now. The base is a placeholder
 * default and is meant to become configurable (a preference / {@code ZulipConfig}
 * field) and this class promoted to a shared {@code koncept} module the AsciiDoc
 * extension and komet can both depend on.
 */
public final class KompendiumUrls {

    /** Placeholder base; the Kompendium site is not built yet (see class doc). */
    public static final String DEFAULT_BASE = "https://ike.network/kompendium";

    private final String base;

    /**
     * @param base the Kompendium site base URL; a trailing slash is trimmed
     */
    public KompendiumUrls(String base) {
        String b = base == null || base.isBlank() ? DEFAULT_BASE : base.trim();
        this.base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    /** A {@code KompendiumUrls} over the placeholder {@link #DEFAULT_BASE}. */
    public static KompendiumUrls defaults() {
        return new KompendiumUrls(DEFAULT_BASE);
    }

    /**
     * The concept's entry page — the "click → entry" target.
     *
     * @param conceptUuid the concept's first public UUID (the join key)
     * @return e.g. {@code https://ike.network/kompendium/concept/<uuid>}
     */
    public URI conceptUrl(UUID conceptUuid) {
        return URI.create(base + "/concept/" + conceptUuid);
    }

    /**
     * The concept's hosted LifeHash identicon PNG, so every medium can reference
     * one image instead of re-embedding it.
     *
     * @param conceptUuid the concept's first public UUID
     * @return e.g. {@code https://ike.network/kompendium/identicon/<uuid>.png}
     */
    public URI identiconUrl(UUID conceptUuid) {
        return URI.create(base + "/identicon/" + conceptUuid + ".png");
    }
}
