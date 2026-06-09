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

import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.time.DateTimeUtil;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.StampEntity;
import network.ike.komet.claude.koncept.IdenticonUriCache;
import network.ike.komet.claude.koncept.KompendiumUrls;
import network.ike.komet.claude.koncept.KonceptIdenticon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The v1 "notify out" entry point: render a concept's current state and version
 * history as a rich Markdown message and post it to the concept's Zulip topic.
 * This is the first rung of the Zulip per-medium adapter (design topic
 * {@code dev-kompendium-zulip}, {@code IKE-Network/ike-issues#620}).
 *
 * <p>The concept-to-channel mapping and the rendering are the parts that will
 * climb the ladder (collapsible detail, then interactive widgets); they are kept
 * here, behind the stable {@link #notifyConcept(int, ViewCalculator)} entry
 * point, so richer rungs are a change to the renderer, not the caller.
 *
 * <p>v1 carries the concept name, its identifier, a linkifier-ready
 * {@code komet:<uuid>} deep-link token, the immediate parents, and the full STAMP
 * history (status, time, author, path per version). The identicon image, the
 * automatic filtered-event trigger, and bring-in are named follow-ups under #620.
 */
public final class ZulipNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(ZulipNotifier.class);

    /** Cap on history rows rendered into a single message. */
    private static final int MAX_HISTORY = 20;

    /** Pixel size of the inline identicon flowed next to a concept's name (≈ text height). */
    private static final int INLINE_IDENTICON_PX = 22;

    /** Stub Kompendium URL scheme for the Koncept entry link (placeholder base). */
    private static final KompendiumUrls KOMPENDIUM = KompendiumUrls.defaults();

    private final ZulipClient client;
    private final String defaultStream;
    /** Realm base URL — the scope key for the persistent identicon-upload cache. */
    private final String realm;

    /**
     * Creates a notifier over a configured client.
     *
     * @param config the realm and bot settings; must not be null
     */
    public ZulipNotifier(ZulipConfig config) {
        Objects.requireNonNull(config, "config");
        this.client = new ZulipClient(config);
        this.defaultStream = config.defaultStream();
        this.realm = config.baseUrl();
    }

    /**
     * Renders a concept's state and history and posts it to its Zulip topic.
     *
     * @param conceptNid the concept to notify on
     * @param view       the active view (supplies name, identifier, STAMP)
     * @return the posted Zulip message id
     * @throws ZulipException on a Zulip API or transport failure
     */
    public long notifyConcept(int conceptNid, ViewCalculator view) {
        Objects.requireNonNull(view, "view");
        ZulipChannel channel = channelForConcept(conceptNid, view);
        String content = renderConcept(conceptNid, view);
        return client.postMessage(channel, content);
    }

    /**
     * Maps a concept to its Zulip channel: the configured default stream, and a
     * topic of the concept's name decorated with its stable identifier.
     *
     * <p>v1 limitation: the name is part of the topic, so a later rename opens a
     * new topic. A follow-up keys the topic on the stable id and tolerates renames.
     *
     * @param conceptNid the concept
     * @param view       the active view
     * @return the channel to post to
     */
    private ZulipChannel channelForConcept(int conceptNid, ViewCalculator view) {
        return ZulipChannel.of(defaultStream, name(conceptNid, view) + " · " + stableId(conceptNid));
    }

    /**
     * Renders the v1 rich-text message: current state plus version history.
     *
     * @param conceptNid the concept
     * @param view       the active view
     * @return Zulip-flavored Markdown
     */
    private String renderConcept(int conceptNid, ViewCalculator view) {
        String name = name(conceptNid, view);
        String id = stableId(conceptNid);
        UUID uuid = firstUuid(conceptNid);
        // Per-message identicon-upload cache: author/module/path recur across history
        // rows, so upload each unique concept's identicon once and reuse the URI.
        Map<Integer, String> icons = new HashMap<>();

        StringBuilder sb = new StringBuilder();
        // Header: the INLINE identicon flowed next to the linked label on one line.
        // Zulip parses an uploaded ![]() image that SHARES a line with text as
        // <img class="inline-image"> — it flows inline with NO grey block frame (the
        // frame only appears when an image is alone on its line). This is the adoc
        // Koncept badge in Zulip, with the exact LifeHash. (The composite KonceptBadge
        // image and KonceptMatrix renderings remain available as banked alternatives.)
        sb.append(renderInlineHeader(conceptNid, name, uuid, id, icons)).append('\n');
        if (uuid != null) {
            // Linkifier-ready deep-link token; a realm linkifier turns
            // `komet:<uuid>` into an open-in-Komet link.
            sb.append("\nkomet:").append(uuid).append('\n');
        }
        sb.append('\n').append(latestStampLine(conceptNid, view)).append('\n');

        int[] parents = view.parentsOf(conceptNid).intStream().toArray();
        if (parents.length > 0) {
            sb.append("\n**Parents**\n");
            int shown = Math.min(parents.length, 12);
            for (int i = 0; i < shown; i++) {
                sb.append("- ").append(konceptInline(parents[i], view, icons)).append('\n');
            }
            if (parents.length > shown) {
                sb.append("- … (").append(parents.length - shown).append(" more)\n");
            }
        }

        sb.append("\n**History**\n\n").append(renderHistory(conceptNid, view, icons));
        return sb.toString();
    }

    /**
     * Renders the header as an INLINE identicon image followed by the linked label and
     * id on one line. Because the {@code ![]()} image shares the line with text, Zulip
     * renders it as an inline image with no block frame. Best-effort: on failure, falls
     * back to the linked text label without an identicon.
     */
    private String renderInlineHeader(int conceptNid, String name, UUID uuid, String id,
                                      Map<Integer, String> icons) {
        String labelMd = (uuid != null)
                ? "**[" + name + "](" + KOMPENDIUM.conceptUrl(uuid) + ")**"
                : "**" + name + "**";
        String uri = iconUri(conceptNid, icons);
        return (uri.isEmpty() ? "" : "![k](" + uri + ") ") + labelMd + "  ·  `" + id + "`";
    }

    /**
     * Returns an uploaded inline-identicon URI for a concept, cached per message so a
     * concept that recurs (author/module/path across history rows) uploads only once.
     * Returns {@code ""} on failure (the caller then omits the identicon).
     */
    private String iconUri(int nid, Map<Integer, String> icons) {
        return icons.computeIfAbsent(nid, this::resolveIconUri);
    }

    /**
     * Resolves a concept's inline-identicon upload URI: the persistent cross-message
     * cache first (upload once, ever), then a fresh upload (cached) on a miss. Returns
     * {@code ""} on failure so the caller omits the identicon.
     */
    private String resolveIconUri(int nid) {
        UUID uuid = firstUuid(nid);
        String cached = IdenticonUriCache.get(realm, uuid);
        if (cached != null) {
            return cached;
        }
        try {
            String idString = PrimitiveData.publicId(nid).idString();
            byte[] ico = KonceptIdenticon.pngAt(idString, INLINE_IDENTICON_PX);
            String uri = client.uploadFile("k-" + nid + ".png", ico, "image/png");
            IdenticonUriCache.put(realm, uuid, uri);
            return uri;
        } catch (RuntimeException e) {
            LOG.warn("Inline identicon upload failed for nid {}: {}", nid, e.toString());
            return "";
        }
    }

    /** A concept rendered as the standard inline Koncept: identicon + name (no link). */
    private String konceptInline(int nid, ViewCalculator view, Map<Integer, String> icons) {
        String uri = iconUri(nid, icons);
        String nm = name(nid, view);
        return uri.isEmpty() ? nm : "![k](" + uri + ") " + nm;
    }

    /** The component's STAMP versions, newest first, as Markdown list rows. */
    private String renderHistory(int conceptNid, ViewCalculator view, Map<Integer, String> icons) {
        try {
            var optEntity = EntityHandle.get(conceptNid).entity();
            if (optEntity.isEmpty()) {
                return "_(no versions)_\n";
            }
            List<EntityVersion> versions = new ArrayList<>();
            for (var version : optEntity.get().versions()) {
                versions.add(version);
            }
            if (versions.isEmpty()) {
                return "_(no versions)_\n";
            }
            versions.sort((a, b) -> Long.compare(b.stamp().time(), a.stamp().time()));
            StringBuilder sb = new StringBuilder();
            sb.append("| Status · Time | Author | Module | Path |\n");
            sb.append("| :-- | :-- | :-- | :-- |\n");
            int shown = Math.min(versions.size(), MAX_HISTORY);
            for (int i = 0; i < shown; i++) {
                StampEntity<?> stamp = versions.get(i).stamp();
                sb.append("| ").append(stamp.state()).append(" · ").append(DateTimeUtil.format(stamp.time()))
                        .append(" | ").append(konceptInline(stamp.authorNid(), view, icons))
                        .append(" | ").append(konceptInline(stamp.moduleNid(), view, icons))
                        .append(" | ").append(konceptInline(stamp.pathNid(), view, icons))
                        .append(" |\n");
            }
            if (versions.size() > shown) {
                sb.append("| … (").append(versions.size() - shown).append(" earlier) | | | |\n");
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return "_(history unavailable: " + e.getMessage() + ")_\n";
        }
    }

    /** The concept's fully specified name, falling back to a preferred description. */
    private static String name(int nid, ViewCalculator view) {
        return view.getPreferredDescriptionTextWithFallbackOrNid(nid);
    }

    /** A "Last edited by … · …" line from the concept's latest STAMP, best-effort. */
    private static String latestStampLine(int conceptNid, ViewCalculator view) {
        try {
            Latest<EntityVersion> latest = view.stampCalculator().latest(conceptNid);
            if (latest.isPresent()) {
                StampEntity<?> stamp = latest.get().stamp();
                return "_Last edited by " + name(stamp.authorNid(), view)
                        + " · " + DateTimeUtil.format(stamp.time()) + "_";
            }
        } catch (RuntimeException ignored) {
            // STAMP unavailable; omit the provenance line rather than fail the notification.
        }
        return "_No version metadata available._";
    }

    /** The concept's first public UUID, or null if none is resolvable. */
    private static UUID firstUuid(int nid) {
        try {
            UUID[] uuids = PrimitiveData.publicId(nid).asUuidArray();
            return uuids.length > 0 ? uuids[0] : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A stable identifier string for topic/keying: the first public UUID, else the nid. */
    private static String stableId(int nid) {
        UUID uuid = firstUuid(nid);
        return uuid != null ? uuid.toString() : ("nid=" + nid);
    }
}
