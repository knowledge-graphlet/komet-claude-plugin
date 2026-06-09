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
import network.ike.komet.claude.koncept.KompendiumUrls;
import network.ike.komet.claude.koncept.KonceptIdenticon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
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

    /** Stub Kompendium URL scheme for the Koncept entry link (placeholder base). */
    private static final KompendiumUrls KOMPENDIUM = KompendiumUrls.defaults();

    /**
     * Module size for the BLOCK identicon (the {@code /user_uploads} fallback): a
     * high value so the 32×32 LifeHash renders at {@code 32 * size} px of crisp
     * solid-cell blocks. Zulip rescales the preview, and downscaling a high-res
     * sharp source stays sharp on high-DPI/retina displays — where upscaling the
     * small 128px source blurs. 16 → 512px (sharp at 2× for a ~256px preview).
     */
    private static final int BLOCK_MODULE_SIZE = 16;

    private final ZulipClient client;
    private final String defaultStream;

    /**
     * Creates a notifier over a configured client.
     *
     * @param config the realm and bot settings; must not be null
     */
    public ZulipNotifier(ZulipConfig config) {
        Objects.requireNonNull(config, "config");
        this.client = new ZulipClient(config);
        this.defaultStream = config.defaultStream();
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

        StringBuilder sb = new StringBuilder();
        // Koncept-standard reference: the uploaded LifeHash identicon, then the label
        // linked to the concept's Kompendium entry (the "click → entry" behaviour), the
        // id, and the open-in-Komet token below. The identicon is best-effort.
        String identiconMd = renderIdenticon(conceptNid);
        if (!identiconMd.isEmpty()) {
            sb.append(identiconMd).append(' ');
        }
        if (uuid != null) {
            sb.append("**[").append(name).append("](")
                    .append(KOMPENDIUM.conceptUrl(uuid)).append(")**");
        } else {
            sb.append("**").append(name).append("**");
        }
        sb.append("  ·  `").append(id).append("`\n");
        if (uuid != null) {
            // Linkifier-ready deep-link token; a realm linkifier turns
            // `komet:<uuid>` into an open-in-Komet link.
            sb.append("komet:").append(uuid).append('\n');
        }
        sb.append('\n').append(latestStampLine(conceptNid, view)).append('\n');

        int[] parents = view.parentsOf(conceptNid).intStream().toArray();
        if (parents.length > 0) {
            sb.append("\n**Parents**\n");
            int shown = Math.min(parents.length, 12);
            for (int i = 0; i < shown; i++) {
                sb.append("- ").append(name(parents[i], view)).append('\n');
            }
            if (parents.length > shown) {
                sb.append("- … (").append(parents.length - shown).append(" more)\n");
            }
        }

        sb.append("\n**History**\n").append(renderHistory(conceptNid, view));
        return sb.toString();
    }

    /**
     * Renders the concept's LifeHash identicon for the message. Prefers an
     * <strong>inline</strong> realm custom emoji ({@code :k_<id>:}) — the adoc-like
     * inline badge, also reusable in human replies — and falls back to a
     * <strong>block</strong> {@code /user_uploads} thumbnail if the bot can't create
     * emoji. Best-effort: returns {@code ""} on total failure (the post still carries
     * the label, id, provenance, and history).
     */
    private String renderIdenticon(int conceptNid) {
        String idString;
        UUID uuid;
        try {
            idString = PrimitiveData.publicId(conceptNid).idString();
            uuid = firstUuid(conceptNid);
        } catch (RuntimeException e) {
            LOG.warn("Identicon id resolution failed for nid {}: {}", conceptNid, e.toString());
            return "";
        }
        // Preferred: inline custom emoji. Zulip standardizes emoji image size, so a
        // modest source suffices; registered idempotently (deterministic identicon).
        String emoji = emojiName(uuid, conceptNid);
        try {
            client.upsertEmoji(emoji, KonceptIdenticon.png(idString, KonceptIdenticon.DISPLAY_MODULE_SIZE));
            return ":" + emoji + ":";
        } catch (RuntimeException e) {
            LOG.info("Inline Koncept emoji unavailable ({}); using block identicon", e.getMessage());
        }
        // Fallback: a block thumbnail rendered at HIGH resolution so the crisp
        // pixel-art blocks stay sharp when Zulip rescales the preview on high-DPI displays.
        try {
            byte[] png = KonceptIdenticon.png(idString, BLOCK_MODULE_SIZE);
            String filename = "identicon-" + (uuid != null ? uuid : conceptNid) + ".png";
            return "[identicon.png](" + client.uploadFile(filename, png, "image/png") + ")";
        } catch (RuntimeException e) {
            LOG.warn("Identicon upload failed for nid {}: {}", conceptNid, e.toString());
            return "";
        }
    }

    /** A stable, Zulip-valid custom-emoji name for a concept, keyed on its UUID. */
    private static String emojiName(UUID uuid, int conceptNid) {
        String key = uuid != null ? uuid.toString().replace("-", "") : "nid" + Math.abs(conceptNid);
        return "k_" + key.substring(0, Math.min(key.length(), 24));
    }

    /** The component's STAMP versions, newest first, as Markdown list rows. */
    private static String renderHistory(int conceptNid, ViewCalculator view) {
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
            int shown = Math.min(versions.size(), MAX_HISTORY);
            for (int i = 0; i < shown; i++) {
                StampEntity<?> stamp = versions.get(i).stamp();
                sb.append("- ").append(stamp.describe()).append('\n');
            }
            if (versions.size() > shown) {
                sb.append("- … (").append(versions.size() - shown).append(" earlier)\n");
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return "_(history unavailable: " + e.getMessage() + ")_\n";
        }
    }

    /** The concept's fully specified name, falling back to a preferred description. */
    private static String name(int nid, ViewCalculator view) {
        return view.getFullyQualifiedNameText(nid)
                .orElseGet(() -> view.getPreferredDescriptionTextWithFallbackOrNid(nid));
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
